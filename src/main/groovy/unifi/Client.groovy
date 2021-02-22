package unifi

import groovy.json.JsonOutput
import groovy.json.JsonSlurper


/**
 *
 * Transpiled version of php lib https://github.com/Art-of-WiFi/UniFi-API-client
 * which may or may not explain the weirdest things in here.
 *
 * the UniFi API client class
 *
 * This UniFi API client class is based on the work done by the following developers:
 *    domwo: http://community.ubnt.com/t5/UniFi-Wireless/little-php-class-for-unifi-api/m-p/603051
 *    fbagnol: https://github.com/fbagnol/class.unifi.php
 * and the API as published by Ubiquiti:
 *    https://www.ubnt.com/downloads/unifi/<UniFi controller version number>/unifi_sh_api
 *
 * @package UniFi_Controller_API_Client_Class* @author Art of WiFi <info@artofwifi.net>
 * @version Release: 1.1.68
 * @license This class is subject to the MIT license that is bundled with this package in the file LICENSE.md
 * @example This directory in the package repository contains a collection of examples:
 *          https://github.com/Art-of-WiFi/UniFi-API-client/tree/master/examples
 */
class Client
{
    /**
     * private and protected properties*/
    private String class_version = '1.1.68'

    protected String baseurl = 'https://127.0.0.1:8443'

    protected String user = ''

    protected String password = ''

    protected String site = 'default'

    protected String version = '6.0.43'

    protected def debug = false

    protected def is_loggedin = false

    protected def is_unifi_os = false

    protected def last_error_message = null

    //keeps the current session info. This is legacy of the php origin of this class.
    //FIXME refactor
    static Map session
    int exec_retries = 0


    /**
     * Construct an instance of the UniFi API client class
     *
     * @param string $user       user name to use when connecting to the UniFi controller
     * @param string $password   password to use when connecting to the UniFi controller
     * @param string $baseurl    optional, base URL of the UniFi controller which *must* include an 'https://' prefix,
     *                            a port suffix (e.g. :8443) is required for non-UniFi OS controllers,
     *                            do not add trailing slashes, default value is 'https://127.0.0.1:8443'
     * @param string $site       optional, short site name to access, defaults to 'default'
     * @param string $version    optional, the version number of the controller
     * @param bool $ssl_verify optional, whether to validate the controller's SSL certificate or not, a value of true is
     *                            recommended for production environments to prevent potential MitM attacks, default value (false)
     *                            disables validation of the controller certificate
     */
    Client( String user, String password, String baseurl = '', String site = '', String version = '', Boolean ssl_verify = false )
    {
        this.user = trim( user )
        this.password = trim( password )
        if ( baseurl )
        {
            check_base_url( baseurl )
            this.baseurl = trim( baseurl )
        }
        if ( site )
        {
            this.site = trim( site )
        }
        if ( version )
        {
            this.version = trim( version )
        }
        if ( ( (Boolean) ssl_verify ) )
        {
            curl_ssl_verify_peer = true
            curl_ssl_verify_host = 2
        }
    }

    /**
     * This method is called as soon as there are no other references to the class instance
     * https://www.php.net/manual/en/language.oop5.decon.php
     *
     * NOTE: to force the class instance to log out when you're done, simply call logout()*/
    def close()
    {
        /**
         * if $_SESSION['unificookie'] is set, do not logout here*/
        if ( session[ 'unificookie' ] )
        {
            return
        }
        /**
         * logout, if needed*/
        if ( is_loggedin )
        {
            logout()
        }
    }

    /**
     * Login to the UniFi controller
     *
     * @return bool returns true upon success
     */
    Boolean login()
    {

        /**
         * if already logged in we skip the login process*/
        if ( is_loggedin )
        {
            return true
        }
        if ( update_unificookie() )
        {
            is_loggedin = true
            return true
        }

        /**
         * execute the cURL request and get the HTTP response code*/
        def response = doRequest( baseurl + ( is_unifi_os ? '/api/auth/login' : '/api/login ' ), [ username: user, password: password ], null, "POST")

        int http_code = response.get( "code" ) as int
        /**
         * based on the HTTP response code we either trigger an error or
         * extract the cookie from the headers*/
        if ( http_code == 400 || http_code == 401 )
        {
            println( "We received the following HTTP response status: {http_code}. Probably a controller login failure" )
            return http_code
        }
        def response_headers = (Map<String, List<String>>) response.get( "headers" )
        def response_body = response.get( "body" )

        /**
         * we are good to extract the cookies*/
        if ( http_code >= 200 && http_code < 400 && response_body )
        {
            if ( response_headers.containsKey( "Set-Cookie" ) )
            {
                def cookies = response_headers.get( "Set-Cookie" ).join( ";" )

                /**
                 * update the cookie value in $_SESSION['unificookie'], if it exists*/
                if ( session[ 'unificookie' ] )
                {
                    session[ 'unificookie' ] = cookies
                }
                if ( debug )
                    println cookies
                is_loggedin = true
                exec_retries = 0
            }
        }
        else
        {
            is_loggedin = false
        }
        return is_loggedin
    }

    /**
     * Logout from the UniFi controller
     *
     * @return bool returns true upon success
     */
    Boolean logout()
    {

        /*
        def ch

        if ( !( ch = get_curl_resource() ) )
        {
          return false
        }
        def curl_options = [ ( CURLOPT_HEADER ): true, ( CURLOPT_POST ): true ]

        headers = [ 'content-length: 0' ]
        def logout_path = '/logout'
        if ( is_unifi_os )
        {
          logout_path = '/api/auth/logout'
          curl_options[ CURLOPT_CUSTOMREQUEST ] = 'POST'
          create_x_csrf_token_header()
        }
        curl_options[ CURLOPT_HTTPHEADER ] = headers
        curl_options[ CURLOPT_URL ] = baseurl + logout_path


        curl_exec( ch, curl_options )
        if ( curl_errno( ch ) )
        {
          trigger_error( 'cURL error: ' + curl_error( ch ) )
        }
        curl_close( ch )*/
        is_loggedin = false
        session[ 'unificookie' ] = null
        return true
    }

    /****************************************************************
     * Functions to access UniFi controller API routes from here:
     ****************************************************************/
    /**
     * Authorize a client device
     *
     * @param string $mac       client MAC address
     * @param int $minutes   minutes (from now) until authorization expires
     * @param int $up        optional, upload speed limit in kbps
     * @param int $down      optional, download speed limit in kbps
     * @param int $megabytes optional, data transfer limit in MB
     * @param int $ap_mac    optional, AP MAC address to which client is connected, should result in faster authorization
     * @return bool              returns true upon success
     */
    def authorize_guest( def mac, def minutes, def up = null, def down = null, def megabytes = null, def ap_mac = null )
    {

        def payload = [ cmd: 'authorize-guest', mac: mac.toLowerCase(), minutes: (int) ( minutes ) ]
        /**
         * if we have received values for up/down/megabytes/ap_mac we append them to the payload array to be submitted*/
        if ( up )
        {
            payload[ 'up' ] = (int) ( up )
        }
        if ( down )
        {
            payload[ 'down' ] = (int) ( down )
        }
        if ( megabytes )
        {
            payload[ 'bytes' ] = (int) ( megabytes )
        }
        if ( ap_mac )
        {
            payload[ 'ap_mac' ] = ap_mac.toLowerCase()
        }
        return fetch_results_boolean( '/api/s/' + site + '/cmd/stamgr', payload )
    }

    /**
     * Unauthorize a client device
     *
     * @param string $mac client MAC address
     * @return bool        returns true upon success
     */
    Boolean unauthorize_guest( String mac )
    {

        def payload = [ cmd: 'unauthorize-guest', mac: mac.toLowerCase() ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/stamgr', payload )
    }

    /**
     * Reconnect a client device
     *
     * @param string $mac client MAC address
     * @return bool        returns true upon success
     */
    Boolean reconnect_sta( String mac )
    {

        def payload = [ cmd: 'kick-sta', mac: mac.toLowerCase() ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/stamgr', payload )
    }

    /**
     * Block a client device
     *
     * @param string $mac client MAC address
     * @return bool        returns true upon success
     */
    Boolean block_sta( String mac )
    {

        def payload = [ cmd: 'block-sta', mac: mac.toLowerCase() ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/stamgr', payload )
    }

    /**
     * Unblock a client device
     *
     * @param string $mac client MAC address
     * @return bool        returns true upon success
     */
    Boolean unblock_sta( String mac )
    {

        def payload = [ cmd: 'unblock-sta', mac: mac.toLowerCase() ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/stamgr', payload )
    }

    /**
     * Forget one or more client devices
     *
     * NOTE:
     * only supported with controller versions 5.9.X and higher, can be
     * slow (up to 5 minutes) on larger controllers
     *
     * @param array $macs array of client MAC addresses (strings)
     * @return bool        returns true upon success
     */
    Boolean forget_sta( def macs )
    {

        def payload = [ cmd: 'forget-sta', macs: array_map( 'strtolower', macs ) ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/stamgr', payload )
    }

    /**
     * Create a new user/client-device
     *
     * @param string $mac           client MAC address
     * @param string $user_group_id _id value for the user group the new user/client-device should belong to which
     *                                   can be obtained from the output of list_usergroups()
     * @param string $name          optional, name to be given to the new user/client-device
     * @param string $note          optional, note to be applied to the new user/client-device
     * @param bool $is_guest      optional, defines whether the new user/client-device is a guest or not
     * @param bool $is_wired      optional, defines whether the new user/client-device is wired or not
     * @return array|bool                returns an array with a single object containing details of the new user/client-device on success, else returns false
     */
    def create_user( String mac, String user_group_id, String name = null, String note = null, Boolean is_guest = null, Boolean is_wired = null )
    {

        def new_user = [ mac: mac.toLowerCase(), usergroup_id: user_group_id ]
        if ( name )
        {
            new_user[ 'name' ] = name
        }
        if ( note )
        {
            new_user[ 'note' ] = note
            new_user[ 'noted' ] = true
        }
        if ( is_guest && is_bool( is_guest ) )
        {
            new_user[ 'is_guest' ] = is_guest
        }
        if ( is_wired && is_bool( is_wired ) )
        {
            new_user[ 'is_wired' ] = is_wired
        }
        def payload = [ objects: [ [ data: new_user ] ] ]
        return fetch_results( '/api/s/' + site + '/group/user', payload )
    }

    /**
     * Add/modify/remove a client-device note
     *
     * @param string $user_id id of the client-device to be modified
     * @param string $note    optional, note to be applied to the client-device, when empty or not set,
     *                         the existing note for the client-device is removed and "noted" attribute set to false
     * @return bool            returns true upon success
     */
    Boolean set_sta_note( String user_id, String note = null )
    {

        def noted = !note ? false : true
        def payload = [ note: note, noted: noted ]
        return fetch_results_boolean( '/api/s/' + site + '/upd/user/' + trim( user_id ), payload )
    }

    /**
     * Add/modify/remove a client device name
     *
     * @param string $user_id id of the client-device to be modified
     * @param string $name    optional, name to be applied to the client device, when empty or not set,
     *                         the existing name for the client device is removed
     * @return bool            returns true upon success
     */
    Boolean set_sta_name( String user_id, String name = null )
    {

        def payload = [ name: name ]
        return fetch_results_boolean( '/api/s/' + site + '/upd/user/' + trim( user_id ), payload )
    }

    /**
     * Fetch 5 minutes site stats
     *
     * NOTES:
     * - defaults to the past 12 hours
     * - this function/method is only supported on controller versions 5.5.* and later
     * - make sure that the retention policy for 5 minutes stats is set to the correct value in
     *   the controller settings
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @return array        returns an array of 5-minute stats objects for the current site
     */
    def stat_5minutes_site( Integer start = null, Integer end = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 12 * 3600 * 1000 : (int) ( start )
        def attribs = [ 'bytes', 'wan-tx_bytes', 'wan-rx_bytes', 'wlan_bytes', 'num_sta', 'lan-num_sta', 'wlan-num_sta', 'time' ]
        def payload = [ attrs: attribs, start: start, end: end ]
        return fetch_results( '/api/s/' + site + '/stat/report/5minutes.site', payload )
    }

    /**
     * Fetch hourly site stats
     *
     * NOTES:
     * - defaults to the past 7*24 hours
     * - "bytes" are no longer returned with controller version 4.9.1 and later
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @return array        returns an array of hourly stats objects for the current site
     */
    def stat_hourly_site( Integer start = null, Integer end = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 7 * 24 * 3600 * 1000 : (int) ( start )
        def attribs = [ 'bytes', 'wan-tx_bytes', 'wan-rx_bytes', 'wlan_bytes', 'num_sta', 'lan-num_sta', 'wlan-num_sta', 'time' ]
        def payload = [ attrs: attribs, start: start, end: end ]
        return fetch_results( '/api/s/' + site + '/stat/report/hourly.site', payload )
    }

    /**
     * Fetch daily site stats
     *
     * NOTES:
     * - defaults to the past 52*7*24 hours
     * - "bytes" are no longer returned with controller version 4.9.1 and later
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @return array        returns an array of daily stats objects for the current site
     */
    def stat_daily_site( Integer start = null, Integer end = null )
    {

        end = !end ? ( time() - time() % 3600 ) * 1000 : (int) ( end )
        start = !start ? end - 52 * 7 * 24 * 3600 * 1000 : (int) ( start )
        def attribs = [ 'bytes', 'wan-tx_bytes', 'wan-rx_bytes', 'wlan_bytes', 'num_sta', 'lan-num_sta', 'wlan-num_sta', 'time' ]
        def payload = [ attrs: attribs, start: start, end: end ]
        return fetch_results( '/api/s/' + site + '/stat/report/daily.site', payload )
    }

    /**
     * Fetch monthly site stats
     *
     * NOTES:
     * - defaults to the past 52 weeks (52*7*24 hours)
     * - "bytes" are no longer returned with controller version 4.9.1 and later
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @return array        returns an array of monthly stats objects for the current site
     */
    def stat_monthly_site( Integer start = null, Integer end = null )
    {

        end = !end ? ( time() - time() % 3600 ) * 1000 : (int) ( end )
        start = !start ? end - 52 * 7 * 24 * 3600 * 1000 : (int) ( start )
        def attribs = [ 'bytes', 'wan-tx_bytes', 'wan-rx_bytes', 'wlan_bytes', 'num_sta', 'lan-num_sta', 'wlan-num_sta', 'time' ]
        def payload = [ attrs: attribs, start: start, end: end ]
        return fetch_results( '/api/s/' + site + '/stat/report/monthly.site', payload )
    }

    /**
     * Fetch 5 minutes stats for a single access point or all access points
     *
     * NOTES:
     * - defaults to the past 12 hours
     * - this function/method is only supported on controller versions 5.5.* and later
     * - make sure that the retention policy for 5 minutes stats is set to the correct value in
     *   the controller settings
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @param string $mac   optional, AP MAC address to return stats for, when empty,
     *                       stats for all APs are returned
     * @return array         returns an array of 5-minute stats objects
     */
    def stat_5minutes_aps( Integer start = null, Integer end = null, String mac = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 12 * 3600 * 1000 : (int) ( start )
        def attribs = [ 'bytes', 'num_sta', 'time' ]
        def payload = [ attrs: attribs, start: start, end: end ]
        if ( mac )
        {
            payload[ 'mac' ] = mac.toLowerCase()
        }
        return fetch_results( '/api/s/' + site + '/stat/report/5minutes.ap', payload )
    }

    /**
     * Fetch hourly stats for a single access point or all access points
     *
     * NOTES:
     * - defaults to the past 7*24 hours
     * - make sure that the retention policy for hourly stats is set to the correct value in
     *   the controller settings
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @param string $mac   optional, AP MAC address to return stats for, when empty,
     *                       stats for all APs are returned
     * @return array         returns an array of hourly stats objects
     */
    def stat_hourly_aps( Integer start = null, Integer end = null, String mac = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 7 * 24 * 3600 * 1000 : (int) ( start )
        def attribs = [ 'bytes', 'num_sta', 'time' ]
        def payload = [ attrs: attribs, start: start, end: end ]
        if ( mac )
        {
            payload[ 'mac' ] = mac.toLowerCase()
        }
        return fetch_results( '/api/s/' + site + '/stat/report/hourly.ap', payload )
    }

    /**
     * Fetch daily stats for a single access point or all access points
     *
     * NOTES:
     * - defaults to the past 7*24 hours
     * - make sure that the retention policy for hourly stats is set to the correct value in
     *   the controller settings
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @param string $mac   optional, AP MAC address to return stats for, when empty,
     *                       stats for all APs are returned
     * @return array         returns an array of daily stats objects
     */
    def stat_daily_aps( Integer start = null, Integer end = null, String mac = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 7 * 24 * 3600 * 1000 : (int) ( start )
        def attribs = [ 'bytes', 'num_sta', 'time' ]
        def payload = [ attrs: attribs, start: start, end: end ]
        if ( mac )
        {
            payload[ 'mac' ] = mac.toLowerCase()
        }
        return fetch_results( '/api/s/' + site + '/stat/report/daily.ap', payload )
    }

    /**
     * Fetch monthly stats for a single access point or all access points
     *
     * NOTES:
     * - defaults to the past 52 weeks (52*7*24 hours)
     * - make sure that the retention policy for hourly stats is set to the correct value in
     *   the controller settings
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @param string $mac   optional, AP MAC address to return stats for, when empty,
     *                       stats for all APs are returned
     * @return array         returns an array of monthly stats objects
     */
    def stat_monthly_aps( Integer start = null, Integer end = null, String mac = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 52 * 7 * 24 * 3600 * 1000 : (int) ( start )
        def attribs = [ 'bytes', 'num_sta', 'time' ]
        def payload = [ attrs: attribs, start: start, end: end ]
        if ( mac )
        {
            payload[ 'mac' ] = mac.toLowerCase()
        }
        return fetch_results( '/api/s/' + site + '/stat/report/monthly.ap', payload )
    }

    /**
     * Fetch 5 minutes stats for a single user/client device
     *
     * NOTES:
     * - defaults to the past 12 hours
     * - only supported with UniFi controller versions 5.8.X and higher
     * - make sure that the retention policy for 5 minutes stats is set to the correct value in
     *   the controller settings
     * - make sure that "Clients Historical Data" has been enabled in the UniFi controller settings in the Maintenance section
     *
     * @param string $mac     MAC address of user/client device to return stats for
     * @param int $start   optional, Unix timestamp in milliseconds
     * @param int $end     optional, Unix timestamp in milliseconds
     * @param array $attribs array containing attributes (strings) to be returned, valid values are:
     *                         rx_bytes, tx_bytes, signal, rx_rate, tx_rate, rx_retries, tx_retries, rx_packets, tx_packets
     *                         default is ['rx_bytes', 'tx_bytes']
     * @return array           returns an array of 5-minute stats objects
     */
    def stat_5minutes_user( String mac, Integer start = null, Integer end = null, def attribs = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 12 * 3600 * 1000 : (int) ( start )
        attribs = !attribs ? [ 'time', 'rx_bytes', 'tx_bytes' ] : array_merge( [ 'time' ], attribs )
        def payload = [ attrs: attribs, start: start, end: end, mac: mac.toLowerCase() ]
        return fetch_results( '/api/s/' + site + '/stat/report/5minutes.user', payload )
    }

    /**
     * Fetch hourly stats for a single user/client device
     *
     * NOTES:
     * - defaults to the past 7*24 hours
     * - only supported with UniFi controller versions 5.8.X and higher
     * - make sure that the retention policy for hourly stats is set to the correct value in
     *   the controller settings
     * - make sure that "Clients Historical Data" has been enabled in the UniFi controller settings in the Maintenance section
     *
     * @param string $mac     MAC address of user/client device to return stats fo
     * @param int $start   optional, Unix timestamp in milliseconds
     * @param int $end     optional, Unix timestamp in milliseconds
     * @param array $attribs array containing attributes (strings) to be returned, valid values are:
     *                         rx_bytes, tx_bytes, signal, rx_rate, tx_rate, rx_retries, tx_retries, rx_packets, tx_packets
     *                         default is ['rx_bytes', 'tx_bytes']
     * @return array           returns an array of hourly stats objects
     */
    def stat_hourly_user( String mac, Integer start = null, Integer end = null, def attribs = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 7 * 24 * 3600 * 1000 : (int) ( start )
        attribs = !attribs ? [ 'time', 'rx_bytes', 'tx_bytes' ] : array_merge( [ 'time' ], attribs )
        def payload = [ attrs: attribs, start: start, end: end, mac: mac.toLowerCase() ]
        return fetch_results( '/api/s/' + site + '/stat/report/hourly.user', payload )
    }

    /**
     * Fetch daily stats for a single user/client device
     *
     * NOTES:
     * - defaults to the past 7*24 hours
     * - only supported with UniFi controller versions 5.8.X and higher
     * - make sure that the retention policy for daily stats is set to the correct value in
     *   the controller settings
     * - make sure that "Clients Historical Data" has been enabled in the UniFi controller settings in the Maintenance section
     *
     * @param string $mac     MAC address of user/client device to return stats for
     * @param int $start   optional, Unix timestamp in milliseconds
     * @param int $end     optional, Unix timestamp in milliseconds
     * @param array $attribs array containing attributes (strings) to be returned, valid values are:
     *                         rx_bytes, tx_bytes, signal, rx_rate, tx_rate, rx_retries, tx_retries, rx_packets, tx_packets
     *                         default is ['rx_bytes', 'tx_bytes']
     * @return array           returns an array of daily stats objects
     */
    def stat_daily_user( String mac, Integer start = null, Integer end = null, def attribs = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 7 * 24 * 3600 * 1000 : (int) ( start )
        attribs = !attribs ? [ 'time', 'rx_bytes', 'tx_bytes' ] : array_merge( [ 'time' ], attribs )
        def payload = [ attrs: attribs, start: start, end: end, mac: mac.toLowerCase() ]
        return fetch_results( '/api/s/' + site + '/stat/report/daily.user', payload )
    }

    /**
     * Fetch monthly stats for a single user/client device
     *
     * NOTES:
     * - defaults to the past 13 weeks (52*7*24 hours)
     * - only supported with UniFi controller versions 5.8.X and higher
     * - make sure that the retention policy for monthly stats is set to the correct value in
     *   the controller settings
     * - make sure that "Clients Historical Data" has been enabled in the UniFi controller settings in the Maintenance section
     *
     * @param string $mac     MAC address of user/client device to return stats for
     * @param int $start   optional, Unix timestamp in milliseconds
     * @param int $end     optional, Unix timestamp in milliseconds
     * @param array $attribs array containing attributes (strings) to be returned, valid values are:
     *                         rx_bytes, tx_bytes, signal, rx_rate, tx_rate, rx_retries, tx_retries, rx_packets, tx_packets
     *                         default is ['rx_bytes', 'tx_bytes']
     * @return array           returns an array of monthly stats objects
     */
    def stat_monthly_user( String mac, Integer start = null, Integer end = null, def attribs = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 13 * 7 * 24 * 3600 * 1000 : (int) ( start )
        attribs = !attribs ? [ 'time', 'rx_bytes', 'tx_bytes' ] : array_merge( [ 'time' ], attribs )
        def payload = [ attrs: attribs, start: start, end: end, mac: mac.toLowerCase() ]
        return fetch_results( '/api/s/' + site + '/stat/report/monthly.user', payload )
    }

    /**
     * Fetch 5 minutes gateway stats
     *
     * NOTES:
     * - defaults to the past 12 hours
     * - this function/method is only supported on controller versions 5.5.* and later
     * - make sure that the retention policy for 5 minutes stats is set to the correct value in
     *   the controller settings
     * - requires a USG
     *
     * @param int $start   optional, Unix timestamp in milliseconds
     * @param int $end     optional, Unix timestamp in milliseconds
     * @param array $attribs array containing attributes (strings) to be returned, valid values are:
     *                        mem, cpu, loadavg_5, lan-rx_errors, lan-tx_errors, lan-rx_bytes,
     *                        lan-tx_bytes, lan-rx_packets, lan-tx_packets, lan-rx_dropped, lan-tx_dropped
     *                        default is ['time', 'mem', 'cpu', 'loadavg_5']
     * @return array          returns an array of 5-minute stats objects for the gateway belonging to the current site
     */
    def stat_5minutes_gateway( Integer start = null, Integer end = null, def attribs = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 12 * 3600 * 1000 : (int) ( start )
        attribs = !attribs ? [ 'time', 'mem', 'cpu', 'loadavg_5' ] : array_merge( [ 'time' ], attribs )
        def payload = [ attrs: attribs, start: start, end: end ]
        return fetch_results( '/api/s/' + site + '/stat/report/5minutes.gw', payload )
    }

    /**
     * Fetch hourly gateway stats
     *
     * NOTES:
     * - defaults to the past 7*24 hours
     * - requires a USG
     *
     * @param int $start   optional, Unix timestamp in milliseconds
     * @param int $end     optional, Unix timestamp in milliseconds
     * @param array $attribs array containing attributes (strings) to be returned, valid values are:
     *                        mem, cpu, loadavg_5, lan-rx_errors, lan-tx_errors, lan-rx_bytes,
     *                        lan-tx_bytes, lan-rx_packets, lan-tx_packets, lan-rx_dropped, lan-tx_dropped
     *                        default is ['time', 'mem', 'cpu', 'loadavg_5']
     * @return array          returns an array of hourly stats objects for the gateway belonging to the current site
     */
    def stat_hourly_gateway( Integer start = null, Integer end = null, def attribs = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 7 * 24 * 3600 * 1000 : (int) ( start )
        attribs = !attribs ? [ 'time', 'mem', 'cpu', 'loadavg_5' ] : array_merge( [ 'time' ], attribs )
        def payload = [ attrs: attribs, start: start, end: end ]
        return fetch_results( '/api/s/' + site + '/stat/report/hourly.gw', payload )
    }

    /**
     * Fetch daily gateway stats
     *
     * NOTES:
     * - defaults to the past 52 weeks (52*7*24 hours)
     * - requires a USG
     *
     * @param int $start   optional, Unix timestamp in milliseconds
     * @param int $end     optional, Unix timestamp in milliseconds
     * @param array $attribs array containing attributes (strings) to be returned, valid values are:
     *                        mem, cpu, loadavg_5, lan-rx_errors, lan-tx_errors, lan-rx_bytes,
     *                        lan-tx_bytes, lan-rx_packets, lan-tx_packets, lan-rx_dropped, lan-tx_dropped
     *                        default is ['time', 'mem', 'cpu', 'loadavg_5']
     * @return array          returns an array of hourly stats objects for the gateway belonging to the current site
     */
    def stat_daily_gateway( Integer start = null, Integer end = null, def attribs = null )
    {

        end = !end ? ( time() - time() % 3600 ) * 1000 : (int) ( end )
        start = !start ? end - 52 * 7 * 24 * 3600 * 1000 : (int) ( start )
        attribs = !attribs ? [ 'time', 'mem', 'cpu', 'loadavg_5' ] : array_merge( [ 'time' ], attribs )
        def payload = [ attrs: attribs, start: start, end: end ]
        return fetch_results( '/api/s/' + site + '/stat/report/daily.gw', payload )
    }

    /**
     * Fetch monthly gateway stats
     *
     * NOTES:
     * - defaults to the past 52 weeks (52*7*24 hours)
     * - requires a USG
     *
     * @param int $start   optional, Unix timestamp in milliseconds
     * @param int $end     optional, Unix timestamp in milliseconds
     * @param array $attribs array containing attributes (strings) to be returned, valid values are:
     *                        mem, cpu, loadavg_5, lan-rx_errors, lan-tx_errors, lan-rx_bytes,
     *                        lan-tx_bytes, lan-rx_packets, lan-tx_packets, lan-rx_dropped, lan-tx_dropped
     *                        default is ['time', 'mem', 'cpu', 'loadavg_5']
     * @return array          returns an array of monthly stats objects for the gateway belonging to the current site
     */
    def stat_monthly_gateway( Integer start = null, Integer end = null, def attribs = null )
    {

        end = !end ? ( time() - time() % 3600 ) * 1000 : (int) ( end )
        start = !start ? end - 52 * 7 * 24 * 3600 * 1000 : (int) ( start )
        attribs = !attribs ? [ 'time', 'mem', 'cpu', 'loadavg_5' ] : array_merge( [ 'time' ], attribs )
        def payload = [ attrs: attribs, start: start, end: end ]
        return fetch_results( '/api/s/' + site + '/stat/report/monthly.gw', payload )
    }

    /**
     * Fetch speed test results
     *
     * NOTES:
     * - defaults to the past 24 hours
     * - requires a USG
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @return array        returns an array of speed test result objects
     */
    def stat_speedtest_results( Integer start = null, Integer end = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 24 * 3600 * 1000 : (int) ( start )
        def payload = [ attrs: [ 'xput_download', 'xput_upload', 'latency', 'time' ], start: start, end: end ]
        return fetch_results( '/api/s/' + site + '/stat/report/archive.speedtest', payload )
    }

    /**
     * Fetch IPS/IDS events
     *
     * NOTES:
     * - defaults to the past 24 hours
     * - requires a USG
     * - supported in UniFi controller versions 5.9.X and higher
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @param int $limit optional, maximum number of events to return, defaults to 10000
     * @return array        returns an array of IPS/IDS event objects
     */
    def stat_ips_events( Integer start = null, Integer end = null, Integer limit = null )
    {

        end = !end ? time() * 1000 : (int) ( end )
        start = !start ? end - 24 * 3600 * 1000 : (int) ( start )
        limit = !limit ? 10000 : (int) ( limit )
        def payload = [ start: start, end: end, _limit: limit ]
        return fetch_results( '/api/s/' + site + '/stat/ips/event', payload )
    }

    /**
     * Fetch login sessions
     *
     * NOTES:
     * - defaults to the past 7*24 hours
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @param string $mac   optional, client MAC address to return sessions for (can only be used when start and end are also provided)
     * @param string $type  optional, client type to return sessions for, can be 'all', 'guest' or 'user'; default value is 'all'
     * @return array         returns an array of login session objects for all devices or a single device
     */
    def stat_sessions( Integer start = null, Integer end = null, String mac = null, String type = 'all' )
    {

        if ( ![ 'all', 'guest', 'user' ].contains( type ) )
        {
            return false
        }
        end = !end ? time() : (int) ( end )
        start = !start ? end - 7 * 24 * 3600 : (int) ( start )
        def payload = [ type: type, start: start, end: end ]
        if ( mac )
        {
            payload[ 'mac' ] = mac.toLowerCase()
        }
        return fetch_results( '/api/s/' + site + '/stat/session', payload )
    }

    /**
     * Fetch latest 'n' login sessions for a single client device
     *
     * NOTES:
     * - defaults to the past 7*24 hours
     *
     * @param string $mac   client MAC address
     * @param int $limit optional, maximum number of sessions to get (default value is 5)
     * @return array         returns an array of login session objects for all devices or a single device
     */
    def stat_sta_sessions_latest( String mac, Integer limit = null )
    {

        limit = !limit ? 5 : (int) ( limit )
        def payload = [ mac: mac.toLowerCase(), _limit: limit, _sort: '-assoc_time' ]
        return fetch_results( '/api/s/' + site + '/stat/session', payload )
    }

    /**
     * Fetch authorizations
     *
     * NOTES:
     * - defaults to the past 7*24 hours
     *
     * @param int $start optional, Unix timestamp in milliseconds
     * @param int $end   optional, Unix timestamp in milliseconds
     * @return array        returns an array of authorization objects
     */
    def stat_auths( Integer start = null, Integer end = null )
    {

        end = !end ? time() : (int) ( end )
        start = !start ? end - 7 * 24 * 3600 : (int) ( start )
        def payload = [ start: start, end: end ]
        return fetch_results( '/api/s/' + site + '/stat/authorization', payload )
    }

    /**
     * Fetch client devices that connected to the site within given timeframe
     *
     * NOTES:
     * - <historyhours> is only used to select clients that were online within that period,
     *   the returned stats per client are all-time totals, irrespective of the value of <historyhours>
     *
     * @param int $historyhours optional, hours to go back (default is 8760 hours or 1 year)
     * @return array               returns an array of client device objects
     */
    def stat_allusers( Integer historyhours = 8760 )
    {

        def payload = [ type: 'all', conn: 'all', within: (int) ( historyhours ) ]
        return fetch_results( '/api/s/' + site + '/stat/alluser', payload )
    }

    /**
     * Fetch guest devices
     *
     * NOTES:
     * - defaults to the past 7*24 hours
     *
     * @param int $within optional, time frame in hours to go back to list guests with valid access (default = 24*365 hours)
     * @return array         returns an array of guest device objects with valid access
     */
    def list_guests( Integer within = 8760 )
    {

        def payload = [ within: (int) ( within ) ]
        return fetch_results( '/api/s/' + site + '/stat/guest', payload )
    }

    /**
     * Fetch online client device(s)
     *
     * @param string $client_mac optional, the MAC address of a single online client device for which the call must be made
     * @return array              returns an array of online client device objects, or in case of a single device request, returns a single client device object
     */
    def list_clients( String client_mac = null )
    {

        return fetch_results( '/api/s/' + site + '/stat/sta/' + trim( client_mac ).toLowerCase() )
    }

    /**
     * Fetch details for a single client device
     *
     * @param string $client_mac optional, client device MAC address
     * @return array              returns an object with the client device information
     */
    def stat_client( String client_mac )
    {

        return fetch_results( '/api/s/' + site + '/stat/user/' + trim( client_mac ).toLowerCase() )
    }

    /**
     * Assign client device to another group
     *
     * @param string $user_id  id of the user device to be modified
     * @param string $group_id id of the user group to assign user to
     * @return bool             returns true upon success
     */
    Boolean set_usergroup( String user_id, String group_id )
    {

        def payload = [ usergroup_id: group_id ]
        return fetch_results_boolean( '/api/s/' + site + '/upd/user/' + trim( user_id ), payload )
    }

    /**
     * Update client fixedip (using REST)
     *
     * @param string $client_id   _id value for the client
     * @param bool $use_fixedip determines whether use_fixedip is true or false
     * @param string $network_id  optional, _id value for the network where the ip belongs to
     * @param string $fixed_ip    optional, IP address, value of client's fixed_ip field
     * @return array               returns an array containing a single object with attributes of the updated client on success
     */
    def edit_client_fixedip( String client_id, Boolean use_fixedip, String network_id = null, String fixed_ip = null )
    {

        if ( !is_bool( use_fixedip ) )
        {
            return false
        }
        def payload = [ _id: client_id, use_fixedip: use_fixedip ]
        if ( use_fixedip )
        {
            if ( network_id )
            {
                payload[ 'network_id' ] = network_id
            }
            if ( fixed_ip )
            {
                payload[ 'fixed_ip' ] = fixed_ip
            }
        }
        return fetch_results( '/api/s/' + site + '/rest/user/' + trim( client_id ), payload, true, 'PUT' )
    }

    /**
     * Fetch user groups
     *
     * @return array returns an array of user group objects
     */
    def list_usergroups()
    {

        return fetch_results( '/api/s/' + site + '/list/usergroup' )
    }

    /**
     * Create user group (using REST)
     *
     * @param string $group_name name of the user group
     * @param int $group_dn   limit download bandwidth in Kbps (default = -1, which sets bandwidth to unlimited)
     * @param int $group_up   limit upload bandwidth in Kbps (default = -1, which sets bandwidth to unlimited)
     * @return array              containing a single object with attributes of the new usergroup ("_id", "name", "qos_rate_max_down", "qos_rate_max_up", "site_id") on success
     */
    def create_usergroup( String group_name, Integer group_dn = -1, Integer group_up = -1 )
    {

        def payload = [ name: group_name, qos_rate_max_down: (int) ( group_dn ), qos_rate_max_up: (int) ( group_up ) ]
        return fetch_results( '/api/s/' + site + '/rest/usergroup', payload )
    }

    /**
     * Modify user group (using REST)
     *
     * @param string $group_id   _id value of the user group
     * @param string $site_id    _id value of the site
     * @param string $group_name name of the user group
     * @param int $group_dn   limit download bandwidth in Kbps (default = -1, which sets bandwidth to unlimited)
     * @param int $group_up   limit upload bandwidth in Kbps (default = -1, which sets bandwidth to unlimited)
     * @return array              returns an array containing a single object with attributes of the updated usergroup on success
     */
    def edit_usergroup( String group_id, String site_id, String group_name, Integer group_dn = -1, Integer group_up = -1 )
    {

        def payload = [ _id: group_id, name: group_name, qos_rate_max_down: (int) ( group_dn ), qos_rate_max_up: (int) ( group_up ), site_id: site_id ]
        return fetch_results( '/api/s/' + site + '/rest/usergroup/' + trim( group_id ), payload, true, 'PUT' )
    }

    /**
     * Delete user group (using REST)
     *
     * @param string $group_id _id value of the user group to delete
     * @return bool             returns true on success
     */
    Boolean delete_usergroup( String group_id )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/usergroup/' + trim( group_id ), null, true, 'DELETE' )
    }

    /**
     * Fetch AP groups
     *
     * @return array  containing the current AP groups on success
     */
    def list_apgroups()
    {

        return fetch_results( '/v2/api/site/' + site + '/apgroups' )
    }

    /**
     * Create AP group
     *
     * @param string $group_name  name to assign to the AP group
     * @param array $device_macs optional, array containing the MAC addresses (strings) of the APs to add to the new group
     * @return object              returns a single object with attributes of the new AP group on success
     */
    Object create_apgroup( String group_name, def device_macs = [ ] )
    {

        def payload = [ device_macs: device_macs, name: group_name ]
        return fetch_results( '/v2/api/site/' + site + '/apgroups', payload )
    }

    /**
     * Modify AP group
     *
     * @param string $group_id    _id value of the AP group to modify
     * @param string $group_name  name to assign to the AP group
     * @param array $device_macs array containing the members of the AP group which overwrites the existing
     *                             group_members (passing an empty array clears the AP member list)
     * @return object              returns a single object with attributes of the updated AP group on success
     */
    Object edit_apgroup( String group_id, String group_name, def device_macs )
    {

        def payload = [ _id: group_id, attr_no_delete: false, name: group_name, device_macs: device_macs ]
        return fetch_results( '/v2/api/site/' + site + '/apgroups/' + trim( group_id ), payload, true, 'PUT' )
    }

    /**
     * Delete AP group
     *
     * @param string $group_id _id value of the AP group to delete
     * @return bool             returns true on success
     */
    Boolean delete_apgroup( String group_id )
    {

        return fetch_results_boolean( '/v2/api/site/' + site + '/apgroups/' + trim( group_id ), null, true, 'DELETE' )
    }

    /**
     * Fetch firewall groups (using REST)
     *
     * @param string $group_id optional, _id value of the single firewall group to list
     * @return array            containing the current firewall groups or the selected firewall group on success
     */
    def list_firewallgroups( String group_id = '' )
    {

        return fetch_results( '/api/s/' + site + '/rest/firewallgroup/' + trim( group_id ) )
    }

    /**
     * Create firewall group (using REST)
     *
     * @param string $group_name    name to assign to the firewall group
     * @param string $group_type    firewall group type; valid values are address-group, ipv6-address-group, port-group
     * @param array $group_members array containing the members of the new group (IPv4 addresses, IPv6 addresses or port numbers)
     *                               (default is an empty array)
     * @return array                 containing a single object with attributes of the new firewall group on success
     */
    def create_firewallgroup( String group_name, String group_type, def group_members = [ ] )
    {

        if ( ![ 'address-group', 'ipv6-address-group', 'port-group' ].contains( group_type ) )
        {
            return false
        }
        def payload = [ name: group_name, group_type: group_type, group_members: group_members ]
        return fetch_results( '/api/s/' + site + '/rest/firewallgroup', payload )
    }

    /**
     * Modify firewall group (using REST)
     *
     * @param string $group_id      _id value of the firewall group to modify
     * @param string $site_id       site_id value of the firewall group to modify
     * @param string $group_name    name of the firewall group
     * @param string $group_type    firewall group type; valid values are address-group, ipv6-address-group, port-group,
     *                               group_type cannot be changed for an existing firewall group!
     * @param array $group_members array containing the members of the group (IPv4 addresses, IPv6 addresses or port numbers)
     *                               which overwrites the existing group_members (default is an empty array)
     * @return array                 containing a single object with attributes of the updated firewall group on success
     */
    def edit_firewallgroup( String group_id, String site_id, String group_name, String group_type, def group_members = [ ] )
    {

        if ( ![ 'address-group', 'ipv6-address-group', 'port-group' ].contains( group_type ) )
        {
            return false
        }
        def payload = [ _id: group_id, name: group_name, group_type: group_type, group_members: group_members, site_id: site_id ]
        return fetch_results( '/api/s/' + site + '/rest/firewallgroup/' + trim( group_id ), payload, true, 'PUT' )
    }

    /**
     * Delete firewall group (using REST)
     *
     * @param string $group_id _id value of the firewall group to delete
     * @return bool             returns true on success
     */
    Boolean delete_firewallgroup( String group_id )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/firewallgroup/' + trim( group_id ), null, true, 'DELETE' )
    }

    /**
     * Fetch firewall rules (using REST)
     *
     * @return array  containing the current firewall rules on success
     */
    def list_firewallrules()
    {

        return fetch_results( '/api/s/' + site + '/rest/firewallrule' )
    }

    /**
     * Fetch static routing settings (using REST)
     *
     * @param string $route_id _id value of the static route to get settings for
     * @return array            containing the static routes and their settings
     */
    def list_routing( String route_id = '' )
    {

        return fetch_results( '/api/s/' + site + '/rest/routing/' + trim( route_id ) )
    }

    /**
     * Fetch health metrics
     *
     * @return array  containing health metric objects
     */
    def list_health()
    {

        return fetch_results( '/api/s/' + site + '/stat/health' )
    }

    /**
     * Fetch dashboard metrics
     *
     * @param boolean $five_minutes when true, return stats based on 5 minute intervals,
     *                               returns hourly stats by default (supported on controller versions 5.5.* and higher)
     * @return array                 containing dashboard metric objects (available since controller version 4.9.1.alpha)
     */
    def list_dashboard( Boolean five_minutes = false )
    {

        def path_suffix = five_minutes ? '?scale=5minutes' : null
        return fetch_results( '/api/s/' + site + '/stat/dashboard' + path_suffix )
    }

    /**
     * Fetch client devices
     *
     * @return array  containing known client device objects
     */
    def list_users()
    {

        return fetch_results( '/api/s/' + site + '/list/user' )
    }

    /**
     * Fetch UniFi devices
     *
     * @param string $device_mac optional, the MAC address of a single UniFi device for which the call must be made
     * @return array              containing known UniFi device objects (or a single device when using the <device_mac> parameter)
     */
    def list_devices( String device_mac = null )
    {

        return fetch_results( '/api/s/' + site + '/stat/device/' + trim( device_mac ).toLowerCase() )
    }

    /**
     * Fetch (device) tags (using REST)
     *
     * NOTES: this endpoint was introduced with controller versions 5.5.X
     *
     * @return array  containing known device tag objects
     */
    def list_tags()
    {

        return fetch_results( '/api/s/' + site + '/rest/tag' )
    }

    /**
     * Fetch rogue/neighboring access points
     *
     * @param int $within optional, hours to go back to list discovered "rogue" access points (default = 24 hours)
     * @return array         containing rogue/neighboring access point objects
     */
    def list_rogueaps( Integer within = 24 )
    {

        def payload = [ within: (int) ( within ) ]
        return fetch_results( '/api/s/' + site + '/stat/rogueap', payload )
    }

    /**
     * Fetch known rogue access points
     *
     * @return array  containing known rogue access point objects
     */
    def list_known_rogueaps()
    {

        return fetch_results( '/api/s/' + site + '/rest/rogueknown' )
    }

    /**
     * Generate backup
     *
     * NOTES:
     * this is an experimental function, please do not use unless you know exactly what you're doing
     *
     * @return string URL from where the backup file can be downloaded once generated
     */
    String generate_backup()
    {

        def payload = [ cmd: 'backup' ]
        return fetch_results( '/api/s/' + site + '/cmd/backup', payload )
    }

    /**
     * Fetch auto backups
     *
     * @return array  containing objects with backup details on success
     */
    def list_backups()
    {

        def payload = [ cmd: 'list-backups' ]
        return fetch_results( '/api/s/' + site + '/cmd/backup', payload )
    }

    /**
     * Fetch sites
     *
     * @return array  containing a list of sites hosted on this controller with some details
     */
    def list_sites()
    {

        return fetch_results( '/api/self/sites' )
    }

    /**
     * Fetch sites stats
     *
     * NOTES: this endpoint was introduced with controller version 5.2.9
     *
     * @return array  containing statistics for all sites hosted on this controller
     */
    def stat_sites()
    {

        return fetch_results( '/api/stat/sites' )
    }

    /**
     * Create a site
     * @param string $description the long name for the new site
     * @return array               containing a single object with attributes of the new site ("_id", "desc", "name") on success
     */
    def create_site( String description )
    {

        def payload = [ desc: description, cmd: 'add-site' ]
        return fetch_results( '/api/s/' + site + '/cmd/sitemgr', payload )
    }

    /**
     * Delete a site
     *
     * @param string $site_id _id value of the site to delete
     * @return bool            true on success
     */
    Boolean delete_site( String site_id )
    {

        def payload = [ site: site_id, cmd: 'delete-site' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/sitemgr', payload )
    }

    /**
     * Change the current site's name
     *
     * NOTES: immediately after being changed, the site is available in the output of the list_sites() function
     *
     * @param string $site_name the new long name for the current site
     * @return bool              true on success
     */
    Boolean set_site_name( String site_name )
    {

        def payload = [ cmd: 'update-site', desc: site_name ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/sitemgr', payload )
    }

    /**
     * Update site country
     *
     * @param string $country_id _id value of the country key
     * @param object |array $payload    stdClass object or associative array containing the configuration to apply to the site, must be a (partial)
     *                                  object/array structured in the same manner as is returned by list_settings() for the section with the "country" key.
     *                                  Valid country codes can be obtained using the list_country_codes() function/method.
     *                                  Do not include the _id property, it is assigned by the controller and returned upon success.
     * @return bool                     true on success
     */
    Boolean set_site_country( String country_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/setting/country/' + trim( country_id ), payload, true, 'PUT' )
    }

    /**
     * Update site locale
     *
     * @param string $locale_id _id value of the locale section
     * @param object |array $payload   stdClass object or associative array containing the configuration to apply to the site, must be a (partial)
     *                                 object/array structured in the same manner as is returned by list_settings() for section with the the "locale" key.
     *                                 Valid timezones can be obtained in Javascript as explained here:
     *                                 https://stackoverflow.com/questions/38399465/how-to-get-list-of-all-timezones-in-javascript
     *                                 or in PHP using timezone_identifiers_list().
     *                                 Do not include the _id property, it is assigned by the controller and returned upon success.
     * @return bool                    true on success
     */
    Boolean set_site_locale( String locale_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/setting/locale/' + trim( locale_id ), payload, true, 'PUT' )
    }

    /**
     * Update site snmp
     *
     * @param string $snmp_id _id value of the snmp section
     * @param object |array $payload stdClass object or associative array containing the configuration to apply to the site, must be a (partial)
     *                               object/array structured in the same manner as is returned by list_settings() for the section with the "snmp" key.
     *                               Do not include the _id property, it is assigned by the controller and returned upon success.
     * @return bool                  true on success
     */
    Boolean set_site_snmp( String snmp_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/setting/snmp/' + trim( snmp_id ), payload, true, 'PUT' )
    }

    /**
     * Update site mgmt
     *
     * @param string $mgmt_id _id value of the mgmt section
     * @param object |array $payload stdClass object or associative array containing the configuration to apply to the site, must be a (partial)
     *                               object/array structured in the same manner as is returned by list_settings() for the section with the "mgmt" key.
     *                               Do not include the _id property, it is assigned by the controller and returned upon success.
     * @return bool                  true on success
     */
    Boolean set_site_mgmt( String mgmt_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/setting/mgmt/' + trim( mgmt_id ), payload, true, 'PUT' )
    }

    /**
     * Update site guest access
     *
     * @param string $guest_access_id _id value of the guest_access section
     * @param object |array $payload         stdClass object or associative array containing the configuration to apply to the site, must be a (partial)
     *                                      object/array structured in the same manner as is returned by list_settings() for the section with the "guest_access" key.
     *                                      Do not include the _id property, it is assigned by the controller and returned upon success.
     * @return bool                         true on success
     */
    Boolean set_site_guest_access( String guest_access_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/setting/guest_access/' + trim( guest_access_id ), payload, true, 'PUT' )
    }

    /**
     * Update site ntp
     *
     * @param string $ntp_id  _id value of the ntp section
     * @param object |array $payload stdClass object or associative array containing the configuration to apply to the site, must be a (partial)
     *                               object/array structured in the same manner as is returned by list_settings() for the section with the "ntp" key.
     *                               Do not include the _id property, it is assigned by the controller and returned upon success.
     * @return bool                  true on success
     */
    Boolean set_site_ntp( String ntp_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/setting/ntp/' + trim( ntp_id ), payload, true, 'PUT' )
    }

    /**
     * Update site connectivity
     *
     * @param string $connectivity_id _id value of the connectivity section
     * @param object |array $payload         stdClass object or associative array containing the configuration to apply to the site, must be a (partial)
     *                                       object/array structured in the same manner as is returned by list_settings() for the section with the "connectivity" key.
     *                                       Do not include the _id property, it is assigned by the controller and returned upon success.
     * @return bool                          true on success
     */
    Boolean set_site_connectivity( String connectivity_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/setting/connectivity/' + trim( connectivity_id ), payload, true, 'PUT' )
    }

    /**
     * Fetch admins
     *
     * @return array  containing administrator objects for selected site
     */
    def list_admins()
    {

        def payload = [ cmd: 'get-admins' ]
        return fetch_results( '/api/s/' + site + '/cmd/sitemgr', payload )
    }

    /**
     * Fetch all admins
     *
     * @return array  containing administrator objects for all sites
     */
    def list_all_admins()
    {

        return fetch_results( '/api/stat/admin' )
    }

    /**
     * Invite a new admin for access to the current site
     *
     * NOTES:
     * - after issuing a valid request, an invite is sent to the email address provided
     * - issuing this command against an existing admin triggers a "re-invite"
     *
     * @param string $name           name to assign to the new admin user
     * @param string $email          email address to assign to the new admin user
     * @param bool $enable_sso     optional, whether or not SSO is allowed for the new admin
     *                                default value is true which enables the SSO capability
     * @param bool $readonly       optional, whether or not the new admin has readonly
     *                                permissions, default value is false which gives the new admin
     *                                Administrator permissions
     * @param bool $device_adopt   optional, whether or not the new admin has permissions to
     *                                adopt devices, default value is false. With versions < 5.9.X this only applies
     *                                when readonly is true.
     * @param bool $device_restart optional, whether or not the new admin has permissions to
     *                                restart devices, default value is false. With versions < 5.9.X this only applies
     *                                when readonly is true.
     * @return bool                   true on success
     */
    Boolean invite_admin( String name, String email, Boolean enable_sso = true, Boolean readonly = false, Boolean device_adopt = false,
                          Boolean device_restart = false )
    {

        def email_valid = filter_var( trim( email ), "FILTER_VALIDATE_EMAIL" )
        if ( !email_valid )
        {
            trigger_error( 'The email address provided is invalid!' )
            return false
        }
        def payload = [ name: trim( name ), email: trim( email ), for_sso: enable_sso, cmd: 'invite-admin', role: 'admin', permissions: [ ] ]
        if ( readonly )
        {
            payload[ 'role' ] = 'readonly'
        }
        if ( device_adopt )
        {
            payload[ 'permissions' ] = [ 'API_DEVICE_ADOPT' ]
        }
        if ( device_restart )
        {
            payload[ 'permissions' ] = [ 'API_DEVICE_RESTART' ]
        }
        return fetch_results_boolean( '/api/s/' + site + '/cmd/sitemgr', payload )
    }

    /**
     * Assign an existing admin to the current site
     *
     * @param string $admin_id       _id value of the admin user to assign, can be obtained using the
     *                                list_all_admins() method/function
     * @param bool $readonly       optional, whether or not the new admin has readonly
     *                                permissions, default value is false which gives the new admin
     *                                Administrator permissions
     * @param bool $device_adopt   optional, whether or not the new admin has permissions to
     *                                adopt devices, default value is false. With versions < 5.9.X this only applies
     *                                when readonly is true.
     * @param bool $device_restart optional, whether or not the new admin has permissions to
     *                                restart devices, default value is false. With versions < 5.9.X this only applies
     *                                when readonly is true.
     * @return bool                   true on success
     */
    Boolean assign_existing_admin( String admin_id, Boolean readonly = false, Boolean device_adopt = false, Boolean device_restart = false )
    {

        def payload = [ cmd: 'grant-admin', admin: trim( admin_id ), role: 'admin', permissions: [ ] ]
        if ( readonly )
        {
            payload[ 'role' ] = 'readonly'
        }
        if ( device_adopt )
        {
            payload[ 'permissions' ] = [ 'API_DEVICE_ADOPT' ]
        }
        if ( device_restart )
        {
            payload[ 'permissions' ] = [ 'API_DEVICE_RESTART' ]
        }
        return fetch_results_boolean( '/api/s/' + site + '/cmd/sitemgr', payload )
    }

    /**
     * Revoke an admin from the current site
     *
     * NOTES:
     * only non-superadmin accounts can be revoked
     *
     * @param string $admin_id _id value of the admin to revoke, can be obtained using the
     *                          list_all_admins() method/function
     * @return bool             true on success
     */
    Boolean revoke_admin( String admin_id )
    {

        def payload = [ cmd: 'revoke-admin', admin: admin_id ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/sitemgr', payload )
    }

    /**
     * Fetch wlan_groups
     *
     * @return array  containing known wlan_groups
     */
    def list_wlan_groups()
    {

        return fetch_results( '/api/s/' + site + '/list/wlangroup' )
    }

    /**
     * Fetch sysinfo
     *
     * @return array  containing known sysinfo data
     */
    def stat_sysinfo()
    {

        return fetch_results( '/api/s/' + site + '/stat/sysinfo' )
    }

    /**
     * Fetch controller status
     *
     * NOTES:
     * login not required
     *
     * @return bool true upon success (controller is online)
     */
    Boolean stat_status()
    {

        return fetch_results_boolean( '/status', null, false )
    }

    /**
     * Fetch full controller status
     *
     * NOTES:
     * login not required
     *
     * @return bool|array  staus array upon success, false upon failure
     */
    def stat_full_status()
    {
        fetch_results_boolean( '/status', null, false )
        return json_decode( get_last_results_raw() )
    }

    /**
     * Fetch device name mappings
     *
     * NOTES:
     * login not required
     *
     * @return bool|array  mappings array upon success, false upon failure
     */
    def list_device_name_mappings()
    {

        fetch_results_boolean( '/dl/firmware/bundles.json', null, false )
        return json_decode( get_last_results_raw() )
    }

    /**
     * Fetch self
     *
     * @return array  containing information about the logged in user
     */
    def list_self()
    {

        return fetch_results( '/api/s/' + site + '/self' )
    }

    /**
     * Fetch vouchers
     *
     * @param int $create_time optional, create time of the vouchers to fetch in Unix timestamp in seconds
     * @return array              containing hotspot voucher objects
     */
    def stat_voucher( Integer create_time = null )
    {

        def payload = ( create_time ) ? [ create_time: (int) ( create_time ) ] : [ : ]
        return fetch_results( '/api/s/' + site + '/stat/voucher', payload )
    }

    /**
     * Fetch payments
     *
     * @param int $within optional, number of hours to go back to fetch payments
     * @return array         containing hotspot payments
     */
    def stat_payment( Integer within = null )
    {

        def path_suffix = ( within ) ? '?within=' + (int) ( within ) : ''
        return fetch_results( '/api/s/' + site + '/stat/payment' + path_suffix )
    }

    /**
     * Create hotspot operator (using REST)
     *
     * @param string $name       name for the hotspot operator
     * @param string $x_password clear text password for the hotspot operator
     * @param string $note       optional, note to attach to the hotspot operator
     * @return bool               true upon success
     */
    Boolean create_hotspotop( String name, String x_password, String note = null )
    {

        def payload = [ name: name, x_password: x_password ]
        if ( note )
        {
            payload[ 'note' ] = trim( note )
        }
        return fetch_results_boolean( '/api/s/' + site + '/rest/hotspotop', payload )
    }

    /**
     * Fetch hotspot operators (using REST)
     *
     * @return array  containing hotspot operators
     */
    def list_hotspotop()
    {

        return fetch_results( '/api/s/' + site + '/rest/hotspotop' )
    }

    /**
     * Create voucher(s)
     *
     * NOTES: please use the stat_voucher() method/function to retrieve the newly created voucher(s) by create_time
     *
     * @param int $minutes   minutes the voucher is valid after activation (expiration time)
     * @param int $count     number of vouchers to create, default value is 1
     * @param int $quota     single-use or multi-use vouchers, value '0' is for multi-use, '1' is for single-use,
     *                           'n' is for multi-use n times
     * @param string $note      note text to add to voucher when printing
     * @param int $up        upload speed limit in kbps
     * @param int $down      download speed limit in kbps
     * @param int $megabytes data transfer limit in MB
     * @return array             containing a single object which contains the create_time(stamp) of the voucher(s) created
     */
    def create_voucher( Integer minutes, Integer count = 1, Integer quota = 0, String note = null, Integer up = null, Integer down = null,
                        Integer megabytes = null )
    {

        def payload = [ cmd: 'create-voucher', expire: (int) ( minutes ), n: (int) ( count ), quota: (int) ( quota ) ]
        if ( !is_null( note ) )
        {
            payload[ 'note' ] = trim( note )
        }
        if ( !is_null( up ) )
        {
            payload[ 'up' ] = (int) ( up )
        }
        if ( !is_null( down ) )
        {
            payload[ 'down' ] = (int) ( down )
        }
        if ( !is_null( megabytes ) )
        {
            payload[ 'bytes' ] = (int) ( megabytes )
        }
        return fetch_results( '/api/s/' + site + '/cmd/hotspot', payload )
    }

    /**
     * Revoke voucher
     *
     * @param string $voucher_id _id value of the voucher to revoke
     * @return bool               true on success
     */
    Boolean revoke_voucher( String voucher_id )
    {

        def payload = [ _id: voucher_id, cmd: 'delete-voucher' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/hotspot', payload )
    }

    /**
     * Extend guest authorization
     *
     * @param string $guest_id _id value of the guest to extend the authorization for
     * @return bool             true on success
     */
    Boolean extend_guest_validity( String guest_id )
    {

        def payload = [ _id: guest_id, cmd: 'extend' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/hotspot', payload )
    }

    /**
     * Fetch port forwarding stats
     *
     * @return array  containing port forwarding stats
     */
    def list_portforward_stats()
    {

        return fetch_results( '/api/s/' + site + '/stat/portforward' )
    }

    /**
     * Fetch DPI stats
     *
     * @return array  containing DPI stats
     */
    def list_dpi_stats()
    {

        return fetch_results( '/api/s/' + site + '/stat/dpi' )
    }

    /**
     * Fetch filtered DPI stats
     *
     * @param string $type       optional, whether to returns stats by app or by category, valid values:
     *                            'by_cat' or 'by_app'
     * @param array $cat_filter optional, array containing numeric category ids to filter by,
     *                            only to be combined with a "by_app" value for $type
     * @return array              containing filtered DPI stats
     */
    def list_dpi_stats_filtered( String type = 'by_cat', def cat_filter = null )
    {

        if ( ![ 'by_cat', 'by_app' ].contains( type ) )
        {
            return false
        }
        def payload = [ type: type ]
        if ( !is_null( cat_filter ) && type == 'by_app' && is_array( cat_filter ) )
        {
            payload[ 'cats' ] = cat_filter
        }
        return fetch_results( '/api/s/' + site + '/stat/sitedpi', payload )
    }

    /**
     * Fetch current channels
     *
     * @return array  containing currently allowed channels
     */
    def list_current_channels()
    {

        return fetch_results( '/api/s/' + site + '/stat/current-channel' )
    }

    /**
     * Fetch country codes
     *
     * NOTES:
     * these codes following the ISO standard:
     * https://en.wikipedia.org/wiki/ISO_3166-1_numeric
     *
     * @return array  containing available country codes
     */
    def list_country_codes()
    {

        return fetch_results( '/api/s/' + site + '/stat/ccode' )
    }

    /**
     * Fetch port forwarding settings
     *
     * @return array  containing port forwarding settings
     */
    def list_portforwarding()
    {

        return fetch_results( '/api/s/' + site + '/list/portforward' )
    }

    /**
     * Fetch port configurations
     *
     * @return array  containing port configurations
     */
    def list_portconf()
    {

        return fetch_results( '/api/s/' + site + '/list/portconf' )
    }

    /**
     * Fetch VoIP extensions
     *
     * @return array  containing VoIP extensions
     */
    def list_extension()
    {

        return fetch_results( '/api/s/' + site + '/list/extension' )
    }

    /**
     * Fetch site settings
     *
     * @return array  containing site configuration settings
     */
    def list_settings()
    {

        return fetch_results( '/api/s/' + site + '/get/setting' )
    }

    /**
     * Adopt a device to the selected site
     *
     * @param string $mac device MAC address
     * @return bool        true on success
     */
    Boolean adopt_device( String mac )
    {

        def payload = [ mac: mac.toLowerCase(), cmd: 'adopt' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/devmgr', payload )
    }

    /**
     * Reboot a device
     *
     * @param string $mac         device MAC address
     * @param string $reboot_type optional, two options: 'soft' or 'hard', defaults to soft
     *                             soft can be used for all devices, requests a plain restart of that device
     *                             hard is special for PoE switches and besides the restart also requests a
     *                             power cycle on all PoE capable ports. Keep in mind that a 'hard' reboot
     *                             does *NOT* trigger a factory-reset.
     * @return bool                true on success
     */
    Boolean restart_device( String mac, String reboot_type = 'soft' )
    {

        def payload = [ cmd: 'restart', mac: mac.toLowerCase() ]
        if ( reboot_type && [ 'soft', 'hard' ].contains( reboot_type ) )
        {
            payload[ 'reboot_type' ] = reboot_type.toLowerCase()
        }
        return fetch_results_boolean( '/api/s/' + site + '/cmd/devmgr', payload )
    }

    /**
     * Force provision of a device
     *
     * @param string $mac device MAC address
     * @return bool        true on success
     */
    Boolean force_provision( String mac )
    {

        def payload = [ mac: mac.toLowerCase(), cmd: 'force-provision' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/devmgr', payload )
    }

    /**
     * Reboot a UniFi CloudKey
     *
     * NOTE:
     * This API call has no effect on UniFi controllers *not* running on a UniFi CloudKey device
     *
     * @return bool true on success
     */
    Boolean reboot_cloudkey()
    {

        def payload = [ cmd: 'reboot' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/system', payload )
    }

    /**
     * Disable/enable an access point (using REST)
     *
     * NOTES:
     * - a disabled device is excluded from the dashboard status and device count and its LED and WLAN are turned off
     * - appears to only be supported for access points
     * - available since controller versions 5.2.X
     *
     * @param string $ap_id   value of _id for the access point which can be obtained from the device list
     * @param bool $disable true disables the device, false enables the device
     * @return bool            true on success
     */
    Boolean disable_ap( String ap_id, Boolean disable )
    {
        def payload = [ disabled: disable ]
        return fetch_results_boolean( '/api/s/' + site + '/rest/device/' + trim( ap_id ), payload, true, "PUT" )
    }

    /**
     * Override LED mode for a device (using REST)
     *
     * NOTES:
     * - available since controller versions 5.2.X
     *
     * @param string $device_id     value of _id for the device which can be obtained from the device list
     * @param string $override_mode off/on/default; "off" disables the LED of the device,
     *                               "on" enables the LED of the device,
     *                               "default" applies the site-wide setting for device LEDs
     * @return bool                  true on success
     */
    Boolean led_override( String device_id, String override_mode )
    {

        if ( ![ 'off', 'on', 'default' ].contains( override_mode ) )
        {
            return false
        }
        def payload = [ led_override: override_mode ]
        return fetch_results_boolean( '/api/s/' + site + '/rest/device/' + trim( device_id ), payload, true, 'PUT' )
    }

    /**
     * Toggle flashing LED of an access point for locating purposes
     *
     * NOTES:
     * replaces the old set_locate_ap() and unset_locate_ap() methods/functions
     *
     * @param string $mac    device MAC address
     * @param bool $enable true enables flashing LED, false disables flashing LED
     * @return bool           true on success
     */
    Boolean locate_ap( String mac, Boolean enable )
    {

        def cmd = enable ? 'set-locate' : 'unset-locate'
        def payload = [ cmd: cmd, mac: mac.toLowerCase() ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/devmgr', payload )
    }

    /**
     * Toggle LEDs of all the access points ON or OFF
     *
     * @param bool $enable true switches LEDs of all the access points ON, false switches them OFF
     * @return bool         true on success
     */
    Boolean site_leds( Boolean enable )
    {

        def payload = [ led_enabled: enable ]
        return fetch_results_boolean( '/api/s/' + site + '/set/setting/mgmt', payload )
    }

    /**
     * Update access point radio settings
     *
     * NOTES:
     * - only supported on pre-5.X.X controller versions
     *
     * @param string $ap_id         the "_id" value for the access point you wish to update
     * @param string $radio         radio to update, default=ng
     * @param int $channel       channel to apply
     * @param int $ht            channel width, default=20
     * @param string $tx_power_mode power level, "low", "medium", or "high"
     * @param int $tx_power      transmit power level, default=0
     * @return bool                   true on success
     */
    Boolean set_ap_radiosettings( String ap_id, String radio, Integer channel, Integer ht, String tx_power_mode, Integer tx_power )
    {

        def payload = [ radio_table: [ radio: radio, channel: channel, ht: ht, tx_power_mode: tx_power_mode, tx_power: tx_power ] ]
        return fetch_results_boolean( '/api/s/' + site + '/upd/device/' + trim( ap_id ), payload )
    }

    /**
     * Assign access point to another WLAN group
     *
     * @param string $type_id   WLAN type, can be either 'ng' (for WLANs 2G (11n/b/g)) or 'na' (WLANs 5G (11n/a/ac))
     * @param string $device_id _id value of the access point to be modified
     * @param string $group_id  _id value of the WLAN group to assign device to
     * @return bool              true on success
     */
    Boolean set_ap_wlangroup( String type_id, String device_id, String group_id )
    {

        if ( ![ 'ng', 'na' ].contains( type_id ) )
        {
            return false
        }
        def payload = [ wlan_overrides: [ ], ( 'wlangroup_id_' + type_id ): group_id ]
        return fetch_results_boolean( '/api/s/' + site + '/upd/device/' + trim( device_id ), payload )
    }

    /**
     * Update guest login settings
     *
     * NOTES:
     * - both portal parameters are set to the same value!
     *
     * @param bool $portal_enabled    enable/disable the captive portal
     * @param bool $portal_customized enable/disable captive portal customizations
     * @param bool $redirect_enabled  enable/disable captive portal redirect
     * @param string $redirect_url      url to redirect to, must include the http/https prefix, no trailing slashes
     * @param string $x_password        the captive portal (simple) password
     * @param int $expire_number     number of units for the authorization expiry
     * @param int $expire_unit       number of minutes within a unit (a value 60 is required for hours)
     * @param string $section_id        value of _id for the site settings section where key = "guest_access", settings can be obtained
     *                                    using the list_settings() function
     * @return bool                       true on success
     */
    Boolean set_guestlogin_settings( Boolean portal_enabled, Boolean portal_customized, Boolean redirect_enabled, String redirect_url,
                                     String x_password, Integer expire_number, Integer expire_unit, String section_id )
    {

        def payload = [ portal_enabled: portal_enabled, portal_customized: portal_customized, redirect_enabled: redirect_enabled, redirect_url:
                redirect_url, x_password    : x_password, expire_number: expire_number, expire_unit: expire_unit, _id: section_id ]
        return fetch_results_boolean( '/api/s/' + site + '/set/setting/guest_access', payload )
    }

    /**
     * Update guest login settings, base
     *
     * @param object |array $payload stdClass object or associative array containing the configuration to apply to the guest login, must be a (partial)
     *                               object/array structured in the same manner as is returned by list_settings() for the "guest_access" section.
     * @return bool                  true on success
     */
    Boolean set_guestlogin_settings_base( def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/set/setting/guest_access', payload )
    }

    /**
     * Update IPS/IDS settings, base
     *
     * @param object |array $payload stdClass object or associative array containing the IPS/IDS settings to apply, must be a (partial)
     *                               object/array structured in the same manner as is returned by list_settings() for the "ips" section.
     * @return bool                  true on success
     */
    Boolean set_ips_settings_base( def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/set/setting/ips', payload )
    }

    /**
     * Update "Super Management" settings, base
     *
     * @param string $settings_id value of _id for the site settings section where key = "super_mgmt", settings can be obtained
     *                                   using the list_settings() function
     * @param object |array $payload     stdClass object or associative array containing the "Super Management" settings to apply, must be a (partial)
     *                                   object/array structured in the same manner as is returned by list_settings() for the "super_mgmt" section.
     * @return bool                      true on success
     */
    Boolean set_super_mgmt_settings_base( String settings_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/set/setting/super_mgmt/' + trim( settings_id ), payload )
    }

    /**
     * Update "Super SMTP" settings, base
     *
     * @param string $settings_id value of _id for the site settings section where key = "super_smtp", settings can be obtained
     *                                   using the list_settings() function
     * @param object |array $payload     stdClass object or associative array containing the "Super SMTP" settings to apply, must be a (partial)
     *                                   object/array structured in the same manner as is returned by list_settings() for the "super_smtp" section.
     * @return bool                      true on success
     */
    Boolean set_super_smtp_settings_base( String settings_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/set/setting/super_smtp/' + trim( settings_id ), payload )
    }

    /**
     * Update "Super Controller Identity" settings, base
     *
     * @param string $settings_id value of _id for the site settings section where key = "super_identity", settings can be obtained
     *                                   using the list_settings() function
     * @param object |array $payload     stdClass object or associative array containing the "Super Controller Identity" settings to apply, must be a (partial)
     *                                   object/array structured in the same manner as is returned by list_settings() for the "super_identity" section.
     * @return bool                      true on success
     */
    Boolean set_super_identity_settings_base( String settings_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/set/setting/super_identity/' + trim( settings_id ), payload )
    }

    /**
     * Rename access point
     *
     * @param string $ap_id  _id of the access point to rename
     * @param string $apname new name to assign to the access point
     * @return bool           true on success
     */
    Boolean rename_ap( String ap_id, String apname )
    {

        def payload = [ name: apname ]
        return fetch_results_boolean( '/api/s/' + site + '/upd/device/' + trim( ap_id ), payload )
    }

    /**
     * Move a device to another site
     *
     * @param string $mac     MAC address of the device to move
     * @param string $site_id _id (24 char string) of the site to move the device to
     * @return bool            true on success
     */
    Boolean move_device( String mac, String site_id )
    {

        def payload = [ site: site_id, mac: mac.toLowerCase(), cmd: 'move-device' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/sitemgr', payload )
    }

    /**
     * Delete a device from the current site
     *
     * @param string $mac MAC address of the device to delete
     * @return bool            true on success
     */
    Boolean delete_device( String mac )
    {

        def payload = [ mac: mac.toLowerCase(), cmd: 'delete-device' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/sitemgr', payload )
    }

    /**
     * Fetch dynamic DNS settings (using REST)
     *
     * @return array  containing dynamic DNS settings
     */
    def list_dynamicdns()
    {

        return fetch_results( '/api/s/' + site + '/rest/dynamicdns' )
    }

    /**
     * Create dynamic DNS settings, base (using REST)
     *
     * @param object |array $payload stdClass object or associative array containing the configuration to apply to the site, must be a
     *                               (partial) object/array structured in the same manner as is returned by list_dynamicdns() for the site.
     * @return bool                  true on success
     */
    Boolean create_dynamicdns( def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/dynamicdns', payload )
    }

    /**
     * Update site dynamic DNS, base (using REST)
     *
     * @param string $dynamicdns_id _id of the settings which can be found with the list_dynamicdns() function
     * @param object |array $payload       stdClass object or associative array containing the configuration to apply to the site, must be a
     *                                     (partial) object/array structured in the same manner as is returned by list_dynamicdns() for the site.
     * @return bool                        true on success
     */
    Boolean set_dynamicdns( String dynamicdns_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/dynamicdns/' + trim( dynamicdns_id ), payload, true, 'PUT' )
    }

    /**
     * Fetch network settings (using REST)
     * @param string $network_id optional, _id value of the network to get settings for
     * @return array              containing (non-wireless) networks and their settings
     */
    def list_networkconf( String network_id = '' )
    {

        return fetch_results( '/api/s/' + site + '/rest/networkconf/' + trim( network_id ) )
    }

    /**
     * Create a network (using REST)
     *
     * @param object |array $payload stdClass object or associative array containing the configuration to apply to the network, must be a (partial)
     *                                object structured in the same manner as is returned by list_networkconf() for the specific network type.
     *                                Do not include the _id property, it is assigned by the controller and returned upon success.
     * @return array|bool             containing a single object with details of the new network on success, else returns false
     */
    def create_network( def payload )
    {

        return fetch_results( '/api/s/' + site + '/rest/networkconf', payload )
    }

    /**
     * Update network settings, base (using REST)
     *
     * @param string $network_id the "_id" value for the network you wish to update
     * @param object |array $payload    stdClass object or associative array containing the configuration to apply to the network, must be a (partial)
     *                                  object/array structured in the same manner as is returned by list_networkconf() for the network.
     * @return bool                     true on success
     */
    Boolean set_networksettings_base( String network_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/networkconf/' + trim( network_id ), payload, true, 'PUT' )
    }

    /**
     * Delete a network (using REST)
     *
     * @param string $network_id _id value of the network which can be found with the list_networkconf() function
     * @return bool                   true on success
     */
    Boolean delete_network( String network_id )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/networkconf/' + trim( network_id ), null, true, 'DELETE' )
    }

    /**
     * Fetch wlan settings (using REST)
     *
     * @param string $wlan_id optional, _id value of the wlan to fetch the settings for
     * @return array           containing wireless networks and their settings, or an array containing a single wireless network when using
     *                         the <wlan_id> parameter
     */
    def list_wlanconf( String wlan_id = null )
    {

        return fetch_results( '/api/s/' + site + '/rest/wlanconf/' + trim( wlan_id ) )
    }

    /**
     * Create a wlan
     *
     * @param string $name             SSID
     * @param string $x_passphrase     new pre-shared key, minimal length is 8 characters, maximum length is 63,
     *                                         assign a value of null when security = 'open'
     * @param string $usergroup_id     user group id that can be found using the list_usergroups() function
     * @param string $wlangroup_id     wlan group id that can be found using the list_wlan_groups() function
     * @param boolean $enabled          optional, enable/disable wlan
     * @param boolean $hide_ssid        optional, hide/unhide wlan SSID
     * @param boolean $is_guest         optional, apply guest policies or not
     * @param string $security         optional, security type (open, wep, wpapsk, wpaeap)
     * @param string $wpa_mode         optional, wpa mode (wpa, wpa2, ..)
     * @param string $wpa_enc          optional, encryption (auto, ccmp)
     * @param boolean $vlan_enabled     optional, enable/disable vlan for this wlan
     * @param int $vlan             optional, vlan id
     * @param boolean $uapsd_enabled    optional, enable/disable Unscheduled Automatic Power Save Delivery
     * @param boolean $schedule_enabled optional, enable/disable wlan schedule
     * @param array $schedule         optional, schedule rules
     * @param array $ap_group_ids     optional, array of ap group ids, required for UniFi controller versions 6.0.X and higher
     * @return bool                      true on success
     */
    Boolean create_wlan( String name, String x_passphrase, String usergroup_id, String wlangroup_id, Boolean enabled = true, Boolean hide_ssid = false,
                         Boolean is_guest = false, String security = 'open', String wpa_mode = 'wpa2', String wpa_enc = 'ccmp',
                         Boolean vlan_enabled = false, Integer vlan = null, Boolean uapsd_enabled = false, Boolean schedule_enabled = false,
                         def schedule = [ ], def ap_group_ids = null )
    {

        def payload = [ name: name, usergroup_id: usergroup_id, wlangroup_id: wlangroup_id, enabled: enabled, hide_ssid: hide_ssid, is_guest: is_guest, security:
                security, wpa_mode: wpa_mode, wpa_enc: wpa_enc, vlan_enabled: vlan_enabled, uapsd_enabled: uapsd_enabled, schedule_enabled: schedule_enabled, schedule:
                                schedule ]
        if ( vlan && vlan_enabled )
        {
            payload[ 'vlan' ] = vlan
        }
        if ( x_passphrase && security != 'open' )
        {
            payload[ 'x_passphrase' ] = x_passphrase
        }
        if ( ap_group_ids && is_array( ap_group_ids ) )
        {
            payload[ 'ap_group_ids' ] = ap_group_ids
        }
        return fetch_results_boolean( '/api/s/' + site + '/add/wlanconf', payload )
    }

    /**
     * Update wlan settings, base (using REST)
     *
     * @param string $wlan_id the "_id" value for the WLAN which can be found with the list_wlanconf() function
     * @param object |array $payload stdClass object or associative array containing the configuration to apply to the wlan, must be a
     *                               (partial) object/array structured in the same manner as is returned by list_wlanconf() for the wlan.
     * @return bool                  true on success
     */
    Boolean set_wlansettings_base( String wlan_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/wlanconf/' + trim( wlan_id ), payload, true, 'PUT' )
    }

    /**
     * Update basic wlan settings
     *
     * @param string $wlan_id      the "_id" value for the WLAN which can be found with the list_wlanconf() function
     * @param string $x_passphrase new pre-shared key, minimal length is 8 characters, maximum length is 63,
     *                             is ignored if set to null
     * @param string $name         optional, SSID
     * @return bool                true on success
     */
    Boolean set_wlansettings( String wlan_id, String x_passphrase, String name = null )
    {

        def payload = [ ]
        payload[ 'x_passphrase' ] = trim( x_passphrase )
        if ( name )
        {
            payload[ 'name' ] = trim( name )
        }
        return set_wlansettings_base( wlan_id, payload )
    }

    /**
     * Disable/Enable wlan
     *
     * @param string $wlan_id the "_id" value for the WLAN which can be found with the list_wlanconf() function
     * @param bool $disable true disables the wlan, false enables it
     * @return bool            true on success
     */
    Boolean disable_wlan( String wlan_id, Boolean disable )
    {

        if ( !is_bool( disable ) )
        {
            return false
        }
        def action = disable ? false : true
        def payload = [ enabled: action ]
        return set_wlansettings_base( wlan_id, payload )
    }

    /**
     * Delete a wlan (using REST)
     *
     * @param string $wlan_id the "_id" value for the WLAN which can be found with the list_wlanconf() function
     * @return bool            true on success
     */
    Boolean delete_wlan( String wlan_id )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/wlanconf/' + trim( wlan_id ), null, true, 'DELETE' )
    }

    /**
     * Update MAC filter for a wlan
     *
     * @param string $wlan_id            the "_id" value for the WLAN which can be found with the list_wlanconf() function
     * @param string $mac_filter_policy  string, "allow" or "deny"; default MAC policy to apply
     * @param bool $mac_filter_enabled true enables the policy, false disables it
     * @param array $macs               must contain valid MAC strings to be placed in the MAC filter list,
     *                                    replacing existing values. Existing MAC filter list can be obtained
     *                                    through list_wlanconf().
     * @return bool                       true on success
     */
    Boolean set_wlan_mac_filter( String wlan_id, String mac_filter_policy, Boolean mac_filter_enabled, /* TODO Collection|Map */ def macs )
    {

        if ( !is_bool( mac_filter_enabled ) )
        {
            return false
        }
        if ( ![ 'allow', 'deny' ].contains( mac_filter_policy ) )
        {
            return false
        }
        macs = array_map( 'strtolower', macs )
        def payload = [ mac_filter_enabled: /* TODO Check casting precedence */ ( (Boolean) mac_filter_enabled ), mac_filter_policy: mac_filter_policy, mac_filter_list:
                macs ]
        return set_wlansettings_base( wlan_id, payload )
    }

    /**
     * Fetch events
     *
     * @param integer $historyhours optional, hours to go back, default value is 720 hours
     * @param integer $start        optional, which event number to start with (useful for paging of results), default value is 0
     * @param integer $limit        optional, number of events to return, default value is 3000
     * @return array                 containing known events
     */
    def list_events( Integer historyhours = 720, Integer start = 0, Integer limit = 3000 )
    {

        def payload = [ _sort: '-time', within: (int) ( historyhours ), type: null, _start: (int) ( start ), _limit: (int) ( limit ) ]
        return fetch_results( '/api/s/' + site + '/stat/event', payload )
    }

    /**
     * Fetch alarms
     *
     * @param array $payload optional, array of flags to filter by
     *                         Example: ["archived" => false, "key" => "EVT_GW_WANTransition"]
     *                         return only unarchived for a specific key
     * @return array           containing known alarms
     */
    def list_alarms( def payload = [ ] )
    {

        return fetch_results( '/api/s/' + site + '/list/alarm', payload )
    }

    /**
     * Count alarms
     *
     * @param bool $archived optional, if true all alarms are counted, if false only non-archived (active) alarms are counted,
     *                         by default all alarms are counted
     * @return array           containing the alarm count
     */
    def count_alarms( Boolean archived = null )
    {

        def path_suffix = !archived ? '?archived=false' : null
        return fetch_results( '/api/s/' + site + '/cnt/alarm' + path_suffix )
    }

    /**
     * Archive alarms(s)
     *
     * @param string $alarm_id optional, _id of the alarm to archive which can be found with the list_alarms() function,
     *                          by default all alarms are archived
     * @return bool             true on success
     */
    Boolean archive_alarm( String alarm_id = null )
    {

        def payload = [ cmd: 'archive-all-alarms' ]
        if ( alarm_id )
        {
            payload = [ _id: alarm_id, cmd: 'archive-alarm' ]
        }
        return fetch_results_boolean( '/api/s/' + site + '/cmd/evtmgr', payload )
    }

    /**
     * Check controller update
     *
     * NOTE:
     * triggers an update of the controller cached known latest version.
     *
     * @return array|bool returns an array with a single object containing details of the current known latest controller version info
     *                    on success, else returns false
     */
    def check_controller_update()
    {

        return fetch_results( '/api/s/' + site + '/stat/fwupdate/latest-version' )
    }

    /**
     * Check firmware update
     *
     * NOTE:
     * triggers a Device Firmware Update in Classic Settings > System settings > Maintenance
     *
     * @return bool returns true upon success
     */
    Boolean check_firmware_update()
    {

        def payload = [ cmd: 'check-firmware-update' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/productinfo', payload )
    }

    /**
     * Upgrade a device to the latest firmware
     *
     * NOTES:
     * - updates the device to the latest STABLE firmware known to the controller
     *
     * @param string $device_mac MAC address of the device to upgrade
     * @return bool               returns true upon success
     */
    Boolean upgrade_device( String device_mac )
    {

        def payload = [ mac: device_mac.toLowerCase() ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/devmgr/upgrade', payload )
    }

    /**
     * Upgrade a device to a specific firmware file
     *
     * NOTES:
     * - updates the device to the firmware file at the given URL
     * - please take great care to select a valid firmware file for the device!
     *
     * @param string $firmware_url URL for the firmware file to upgrade the device to
     * @param string $device_mac   MAC address of the device to upgrade
     * @return bool                 returns true upon success
     */
    Boolean upgrade_device_external( String firmware_url, String device_mac )
    {

        def payload = [ url: filter_var( firmware_url, "FILTER_SANITIZE_URL" ), mac: device_mac.toLowerCase() ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/devmgr/upgrade-external', payload )
    }

    /**
     * Start rolling upgrade
     *
     * NOTES:
     * - updates all access points to the latest firmware known to the controller in a
     *   staggered/rolling fashion
     *
     * @return bool returns true upon success
     */
    Boolean start_rolling_upgrade()
    {

        def payload = [ cmd: 'set-rollupgrade' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/devmgr', payload )
    }

    /**
     * Cancel rolling upgrade
     *
     * @return bool returns true upon success
     */
    Boolean cancel_rolling_upgrade()
    {

        def payload = [ cmd: 'unset-rollupgrade' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/devmgr', payload )
    }

    /**
     * Fetch firmware versions
     *
     * @param string $type optional, "available" or "cached", determines which firmware types to return,
     *                      default value is "available"
     * @return array        containing firmware versions
     */
    def list_firmware( String type = 'available' )
    {

        if ( ![ 'available', 'cached' ].contains( type ) )
        {
            return false
        }
        def payload = [ cmd: 'list-' + type ]
        return fetch_results( '/api/s/' + site + '/cmd/firmware', payload )
    }

    /**
     * Power-cycle the PoE output of a switch port
     *
     * NOTES:
     * - only applies to switches and their PoE ports...
     * - port must be actually providing power
     *
     * @param string $switch_mac main MAC address of the switch
     * @param int $port_idx   port number/index of the port to be affected
     * @return bool               returns true upon success
     */
    Boolean power_cycle_switch_port( String switch_mac, Integer port_idx )
    {

        def payload = [ mac: switch_mac.toLowerCase(), port_idx: (int) ( port_idx ), cmd: 'power-cycle' ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/devmgr', payload )
    }

    /**
     * Trigger an RF scan by an AP
     *
     * @param string $ap_mac MAC address of the AP
     * @return bool           returns true upon success
     */
    Boolean spectrum_scan( String ap_mac )
    {

        def payload = [ cmd: 'spectrum-scan', mac: ap_mac.toLowerCase() ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/devmgr', payload )
    }

    /**
     * Check the RF scanning state of an AP
     *
     * @param string $ap_mac MAC address of the AP
     * @return object         containing relevant information (results if available) regarding the RF scanning state of the AP
     */
    Object spectrum_scan_state( String ap_mac )
    {

        return fetch_results( '/api/s/' + site + '/stat/spectrum-scan/' + trim( ap_mac ).toLowerCase() )
    }

    /**
     * Update device settings, base (using REST)
     *
     * @param string $device_id _id of the device which can be found with the list_devices() function
     * @param object |array $payload   stdClass object or associative array containing the configuration to apply to the device, must be a
     *                                 (partial) object/array structured in the same manner as is returned by list_devices() for the device.
     * @return bool                    true on success
     */
    Boolean set_device_settings_base( String device_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/device/' + trim( device_id ), payload, true, 'PUT' )
    }

    /**
     * Fetch Radius profiles (using REST)
     *
     * NOTES:
     * - this function/method is only supported on controller versions 5.5.19 and later
     *
     * @return array objects containing all Radius profiles for the current site
     */
    def list_radius_profiles()
    {

        return fetch_results( '/api/s/' + site + '/rest/radiusprofile' )
    }

    /**
     * Fetch Radius user accounts (using REST)
     *
     * NOTES:
     * - this function/method is only supported on controller versions 5.5.19 and later
     *
     * @return array objects containing all Radius accounts for the current site
     */
    def list_radius_accounts()
    {

        return fetch_results( '/api/s/' + site + '/rest/account' )
    }

    /**
     * Create a Radius user account (using REST)
     *
     * NOTES:
     * - this function/method is only supported on controller versions 5.5.19 and later
     *
     * @param string $name               name for the new account
     * @param string $x_password         password for the new account
     * @param int $tunnel_type        must be one of the following values:
     *                                    1      Point-to-Point Tunneling Protocol (PPTP)
     *                                    2      Layer Two Forwarding (L2F)
     *                                    3      Layer Two Tunneling Protocol (L2TP)
     *                                    4      Ascend Tunnel Management Protocol (ATMP)
     *                                    5      Virtual Tunneling Protocol (VTP)
     *                                    6      IP Authentication Header in the Tunnel-mode (AH)
     *                                    7      IP-in-IP Encapsulation (IP-IP)
     *                                    8      Minimal IP-in-IP Encapsulation (MIN-IP-IP)
     *                                    9      IP Encapsulating Security Payload in the Tunnel-mode (ESP)
     *                                    10     Generic Route Encapsulation (GRE)
     *                                    11     Bay Dial Virtual Services (DVS)
     *                                    12     IP-in-IP Tunneling
     *                                    13     Virtual LANs (VLAN)
     * @param int $tunnel_medium_type must be one of the following values:
     *                                    1      IPv4 (IP version 4)
     *                                    2      IPv6 (IP version 6)
     *                                    3      NSAP
     *                                    4      HDLC (8-bit multidrop)
     *                                    5      BBN 1822
     *                                    6      802 (includes all 802 media plus Ethernet "canonical format")
     *                                    7      E.163 (POTS)
     *                                    8      E.164 (SMDS, Frame Relay, ATM)
     *                                    9      F.69 (Telex)
     *                                    10     X.121 (X.25, Frame Relay)
     *                                    11     IPX
     *                                    12     Appletalk
     *                                    13     Decnet IV
     *                                    14     Banyan Vines
     *                                    15     E.164 with NSAP format subaddress
     * @param int $vlan               optional, VLAN to assign to the account
     * @return array                      containing a single object for the newly created account upon success, else returns false
     */
    def create_radius_account( String name, String x_password, Integer tunnel_type, Integer tunnel_medium_type, Integer vlan = null )
    {

        def tunnel_types = [ 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13 ]
        def tunnel_medium_types = [ 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 ]
        if ( !tunnel_types.contains( tunnel_type ) || !tunnel_medium_types.contains( tunnel_medium_type ) )
        {
            return false
        }
        def payload = [ name: name, x_password: x_password, tunnel_type: /* TODO Check casting precedence */ ( (int) tunnel_type ), tunnel_medium_type: /* TODO Check casting precedence */ ( (int)
                tunnel_medium_type ) ]
        if ( !is_null( vlan ) )
        {
            payload[ 'vlan' ] = /* TODO Check casting precedence */ ( (int) vlan )
        }
        return fetch_results( '/api/s/' + site + '/rest/account', payload )
    }

    /**
     * Update Radius account, base (using REST)
     *
     * NOTES:
     * - this function/method is only supported on controller versions 5.5.19 and later
     *
     * @param string $account_id _id of the account which can be found with the list_radius_accounts() function
     * @param object |array $payload    stdClass object or associative array containing the new profile to apply to the account, must be a (partial)
     *                                  object/array structured in the same manner as is returned by list_radius_accounts() for the account.
     * @return bool                     true on success
     */
    Boolean set_radius_account_base( String account_id, def payload )
    {

        return fetch_results_boolean( '/api/s/' + site + '/rest/account/' + trim( account_id ), payload, true, 'PUT' )
    }

    /**
     * Delete a Radius account (using REST)
     *
     * NOTES:
     * - this function/method is only supported on controller versions 5.5.19 and later
     *
     * @param string $account_id _id of the account which can be found with the list_radius_accounts() function
     * @return bool               true on success
     */
    Boolean delete_radius_account( String account_id )
    {
        return fetch_results_boolean( '/api/s/' + site + '/rest/account/' + trim( account_id ), null, true, 'DELETE' )
    }

    /**
     * Execute specific stats command
     *
     * @param string $command command to execute, known valid values:
     *                         'reset-dpi', resets all DPI counters for the current site
     * @return bool            true on success
     */
    Boolean cmd_stat( String command )
    {

        if ( ![ 'reset-dpi' ].contains( command ) )
        {
            return false
        }
        def payload = [ cmd: trim( command ) ]
        return fetch_results_boolean( '/api/s/' + site + '/cmd/stat', payload )
    }

    /**
     * Toggle Element Adoption ON or OFF
     *
     * @param bool $enable true enables Element Adoption, false disables Element Adoption
     * @return bool         true on success
     */
    Boolean set_element_adoption( Boolean enable )
    {

        if ( !is_bool( enable ) )
        {
            return false
        }
        def payload = [ enabled: enable ]
        return fetch_results_boolean( '/api/s/' + site + '/set/setting/element_adopt', payload )
    }


    /****************************************************************
     * setter/getter functions from here:
     ****************************************************************/
    /**
     * Modify the private property $site
     *
     * NOTE:
     * this method is useful to switch between sites
     *
     * @param string $site string; must be the short site name of a site to which the
     *                              provided credentials have access
     * @return string               the new (short) site name
     */
    def set_site( def site )
    {
        this.site = trim( site )
        return this.site
    }

    /**
     * Get the private property $site
     *
     * @return string the current (short) site name
     */
    String get_site()
    {

        return site
    }

    /**
     * Set debug mode
     *
     * @param bool $enable true enables debug mode, false disables debug mode
     * @return bool         false when a non-boolean parameter was passed
     */
    Boolean set_debug( Boolean enable )
    {
        debug = enable
        return debug
    }

    /**
     * Get the private property $debug
     *
     * @return bool the current boolean value for $debug
     */
    Boolean get_debug()
    {
        return debug
    }


    /**
     * Get last error message
     *
     * @return object|bool the error message of the last method called in PHP stdClass Object format, returns false if unavailable
     */
    def get_last_error_message()
    {
        return last_error_message
    }


    /**
     * Set value for private property $is_unifi_os
     *
     * @param bool |int $is_unifi_os new value, must be 0, 1, true or false
     * @return bool                  whether request was successful or not
     */
    void set_is_unifi_os( def is_unifi_os )
    {
        this.is_unifi_os = is_unifi_os
    }


    /****************************************************************
     * private and protected functions from here:
     ****************************************************************/
    /**
     * Fetch results
     *
     * execute the cURL request and return results
     *
     * @param string $path           request path
     * @param object |array $payload        optional, PHP associative array or stdClass Object, payload to pass with the request
     * @param boolean $boolean        optional, whether the method should return a boolean result, else return
     *                                      the "data" array
     * @param boolean $login_required optional, whether the method requires to be logged in or not
     * @return bool|array                   [description]
     */
    protected def fetch_results( def path, def payload = null,
                                 def _boolean = false, def login_required = true, String method = "GET" )
    {

        /**
         * guard clause to check if we are logged in when needed*/
        if ( login_required && !is_loggedin )
        {
            return false
        }

        def last_results_raw = exec_curl( path, payload, method ) as String

        def response = json_decode( last_results_raw )

        if ( response.meta.rc )
        {
            if ( response.meta.rc == 'ok' )
            {
                last_error_message = null
                if ( is_array( response.data ) && !_boolean )
                {
                    return response.data
                }
                return true
            }
            else if ( response.meta.rc == 'error' )
            {
                /**
                 * we have an error:
                 * set $this->set last_error_message if the returned error message is available*/
                if ( response.meta.msg )
                {
                    last_error_message = response.meta.msg
                    if ( debug )
                    {
                        trigger_error( 'Last error message: ' + last_error_message )
                    }
                }
            }
        }
        /**
         * to deal with a response coming from the new v2 API*/
        if ( path.contains( '/v2/api/' ) )
        {
            if ( response.errorCode )
            {
                if ( response.message )
                {
                    last_error_message = response.message
                    if ( debug )
                    {
                        trigger_error( 'Debug: Last error message: ' + last_error_message )
                    }
                }
            }
            else
            {
                return response
            }
        }

        return false
    }

    /**
     * Fetch results where output should be boolean (true/false)
     *
     * execute the cURL request and return a boolean value
     *
     * @param string $path           request path
     * @param object |array $payload        optional, PHP associative array or stdClass Object, payload to pass with the request
     * @param bool $login_required optional, whether the method requires to be logged in or not
     * @return bool                         [description]
     */
    protected Boolean fetch_results_boolean( String path, def payload = null, Boolean login_required = true, String method = "POST" )
    {

        return fetch_results( path, payload, true, login_required, method )
    }


    /**
     * Validate the submitted base URL
     *
     * @param string $baseurl the base URL to validate
     * @return bool            true if base URL is a valid URL, else returns false
     */
    protected Boolean check_base_url( String baseurl )
    {

        if ( !filter_var( baseurl, "FILTER_VALIDATE_URL" ) || baseurl.endsWith( '/' ) )
        {
            trigger_error( 'The URL provided is incomplete, invalid or ends with a / character!' )
            return false
        }
        return true
    }


    /**
     */
    /**
     * Update the unificookie if sessions are enabled
     *
     * @return bool true when unificookie was updated, else returns false
     */
    protected def update_unificookie()
    {

        if ( session[ 'unificookie' ] )
        {
            def cookies = session[ 'unificookie' ]
            /**
             * if we have a JWT in our cookie we know we're dealing with a UniFi OS controller*/
            if ( cookies?.contains( 'TOKEN' ) )
            {
                is_unifi_os = true
            }
            return true
        }
        return false
    }

    /**
     * Add a header containing the CSRF token from our Cookie string
     *
     * @return bool true upon success or false when unable to extract the CSRF token
     */
    protected Map create_x_csrf_token_header()
    {

        if ( !is_unifi_os )
            return [ : ]

        /* jwt_payload introduced in subscope Stmt_If on line 3729*/
        def jwt_payload
        /* jwt introduced in subscope Stmt_If on line 3720*/
        def jwt
        def cookie_bits = session[ 'unificookie' ]?.split( '=' )
        if ( cookie_bits?.size() > 1 )
        {
            jwt = cookie_bits[ 1 ]
        }
        else
        {
            return [ : ]
        }
        def jwt_components = jwt?.split( '.' )
        if ( jwt_components?.size() > 1 )
        {
            jwt_payload = jwt_components[ 1 ]
        }
        else
        {
            return [ : ]
        }
        return [ 'x-csrf-token': json_decode( Base64.decoder.decode( jwt_payload as String) ).csrfToken ]

    }

    /**
     * Execute the cURL request
     *
     * @param string $path    path for the request
     * @param object |array $payload optional, payload to pass with the request
     * @return bool|array|string     response returned by the controller API, false upon error
     */
    protected def exec_curl( String path, def payload = null, String request_method )
    {

        /* url introduced in subscope Stmt_ClassMethod on line 3750*/
        def url

        Map headers = [ : ]
        def json_payload = ''
        if ( is_unifi_os )
        {
            url = baseurl + '/proxy/network' + path
        }
        else
        {
            url = baseurl + path
        }

        /**
         * what we do when a payload is passed*/
        if ( payload )
        {
            json_payload = json_encode( payload )
            headers = [ 'content-type': 'application/json', 'content-length': json_payload.length() ] + create_x_csrf_token_header()
            /**
             * we shouldn't be using GET (the default request type) or DELETE when passing a payload,
             * switch to POST instead*/
            if ( request_method == 'GET' || request_method == 'DELETE' )
            {
                request_method = 'POST'
            }
        }

        def response = doRequest( url, payload, headers, request_method )
        int http_code = response.get( "code" ) as int
        def response_headers = (Map<String, List<String>>) response.get( "headers" )
        def response_body = response.get( "body" )
        if ( http_code == 400 || http_code == 401 )
        {
            println( "We received the following HTTP response status: {http_code}. Probably a controller login failure" )
            return http_code
        }

        /**
         * an HTTP response code 401 (Unauthorized) indicates the Cookie/Token has expired in which case
         * we need to login again.*/
        if ( http_code == 401 )
        {
            if ( debug )
            {
                println( 'exec_curl' + ': needed to reconnect to UniFi controller' )
            }
            if ( exec_retries == 0 )
            {
                /**
                 * explicitly clear the expired Cookie/Token, update other properties and log out before logging in again*/
                session[ 'unificookie' ] = null
                is_loggedin = false
                exec_retries++
                /**
                 * then login again*/
                login()
                /**
                 * when re-login was successful, simply execute the same cURL request again*/
                if ( is_loggedin )
                {
                    if ( debug )
                    {
                        println( 'exec_curl' + ': re-logged in, calling exec_curl again' )
                    }
                    return exec_curl( path, payload, method )
                }
                if ( debug )
                {
                    println( 'exec_curl' + ': re-login failed' )
                }
            }
            return false
        }

        return response
    }


    Map<String, Object> doRequest( String url, Map<String, String> body, Map headers, String method )
    {
        Map<String, Object> postResult = new HashMap<>()
        try
        {
            ( (HttpURLConnection) new URL( url ).openConnection() ).with( {
                headers.each { k, v -> it.setRequestProperty( k as String, v ) }
                requestMethod = method
                doOutput = true
                if(body)
                    outputStream.withPrintWriter( { printWriter -> printWriter.write( body instanceof String ? body : JsonOutput.toJson( body ) )
                    } )
                postResult.put( "code", responseCode )
                postResult.put( "body", inputStream.text )
                postResult.put( "headers", headerFields )
            } )
        }
        catch ( e )
        {
            e.printStackTrace()
            postResult.put( "error", e.getMessage() )
        }
        if ( debug )
        {
            println '<pre>'
            println url
            println '-----------LOGIN-------------'
            println( body.toString() )
            println '----------RESPONSE-----------'
            println postResult.toString()
            println '-----------------------------'
            println '</pre>'
        }
        return postResult
    }

    void trigger_error( String s )
    {
        throw new RuntimeException( s )
    }


    def filter_var( String s, String o )
    {
        switch ( o )
        {
            case "FILTER_VALIDATE_URL": try
            {
                return new URL( s )
            }
            catch ( e )
            {
                return false
            }
            case "FILTER_VALIDATE_EMAIL": return s.
                    matches(
                            "(?:[a-z0-9!#\$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#\$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])" )
            case "FILTER_SANITIZE_URL": return new URL( s ).toString()
            default: return false
        }
    }

    def json_decode( def a )
    {
        new JsonSlurper().parse( a )
    }

    def is_null( def s )
    {
        return s != null
    }

    boolean is_array( Object o )
    {
        return o != null && ( o instanceof Collection || o.getClass().isArray() )
    }

    static String trim( String s )
    {
        s.trim()
    }

    boolean is_bool( boolean aBoolean )
    {
        return aBoolean
    }

    def array_map( String s, Object o )
    {
        return [ o ] //fixme or delete
    }

    String json_encode( Object o )
    {
        JsonOutput.toJson( o )
    }
}
