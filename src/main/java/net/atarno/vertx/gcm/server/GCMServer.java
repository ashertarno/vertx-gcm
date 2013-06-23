/*
* Copyright 2012-2013 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package net.atarno.vertx.gcm.server;

import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.VoidHandler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.http.HttpClientRequest;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;

/**
 * GCM Server busmod<p>
 * Please see the manual for a full description<p>
 *
 * @author <a href="mailto:atarno@gmail.com">Asher Tarnopolski</a>
 *         <p/>
 *         <strong>Note: </strong>This class makes use of code published by Google.
 *         <p/>
 *         <a href="https://code.google.com/p/gcm/source/browse/gcm-server/src/com/google/android/gcm/server/Sender.java">Source code</a>
 *         <p/>
 */
public class GCMServer extends BusModBase implements Handler<Message<JsonObject>> {
    private String address;
    private String gcm_url;
    private int gcm_port;
    private int gcm_registration_ids_limit;
    private int gcm_max_seconds_to_leave;
    private int gcm_backoff_retries;
    private int gcm_min_backoff_delay;
    private int gcm_max_backoff_delay;
    private URI uri;

    @Override
    public void start() {
        super.start();
        address = getOptionalStringConfig( "address", "vertx.gcm" );
        gcm_registration_ids_limit = getOptionalIntConfig( "gcm_registration_ids_limit", 1000 );//gcm default
        gcm_max_seconds_to_leave = getOptionalIntConfig( "gcm_max_seconds_to_leave", 2419200 );//gcm default
        gcm_backoff_retries = getOptionalIntConfig( "gcm_backoff_retries", 5 );
        gcm_url = getOptionalStringConfig( "gcm_url", "https://android.googleapis.com/gcm/send" );
        gcm_port = getOptionalIntConfig( "gcm_port", 443 );

        gcm_max_backoff_delay = 1024000;//gcm default
        gcm_min_backoff_delay = 1000;//gcm default

        try {
            uri = new URI( voidNull( gcm_url ) );
        }
        catch ( URISyntaxException e ) {
        }

        eb.registerHandler( address, this );
        logger.debug( "GCMServer worker was registered as " + address );
    }

    @Override
    public void stop() {
        logger.debug( "GCMServer worker " + address + " was unregistered" );
    }

    @SuppressWarnings( "ConstantConditions" )
    @Override
    public void handle( Message<JsonObject> message ) {

        if ( uri == null ) {
            sendError( message, "'" + gcm_url + "' is an illegal value for config parameter 'gcm_url'" );
            return;
        }
        String apiKey = voidNull( message.body().getString( "api_key" ) );
        if ( apiKey.isEmpty() ) {
            sendError( message, "Missing mandatory field 'api_key'" );
            return;
        }

        JsonObject n = message.body().getObject( "notification" );
        if ( n == null ) {
            sendError( message, "Missing mandatory field 'notification'" );
            return;
        }

        int ttl = n.getInteger( "time_to_live" );
        if ( ttl > gcm_max_seconds_to_leave ) {
            sendError( message, "Max value of 'time_to_live' exceeded: " + ttl + " > " + gcm_max_seconds_to_leave );
            return;
        }

        JsonArray regIds = n.getArray( "registration_ids" );
        if ( regIds == null || regIds.size() == 0 ) {
            sendError( message, "Missing mandatory non-empty field 'registration_ids'" );
            return;
        }
        if ( regIds.size() > gcm_registration_ids_limit ) {
            sendError( message, "Max size of 'registration_ids' exceeded: " + regIds.size() + " > " + gcm_registration_ids_limit );
            return;
        }

        logger.debug( "Ready to push notification: " + message.body().encode() );

        JsonObject notif = n.copy();

        try {
            send( message, notif, apiKey );
        }
        catch ( Exception e ) {
            sendError( message, e.getMessage() );
        }
    }

    private JsonObject calculateSummary( JsonArray regIds, HashMap<String, JsonObject> response, long multicastId ) {
        int success = 0;
        int failure = 0;
        int canonicalIds = 0;
        JsonArray deliveries = new JsonArray();
        for ( Object regId : regIds ) {
            JsonObject result = response.get( regId );
            if ( !voidNull( result.getString( "message_id" ) ).isEmpty() ) {
                success++;
                if ( !voidNull( result.getString( "registration_id" ) ).isEmpty() ) {
                    canonicalIds++;
                }
            }
            else {
                failure++;
            }
            // add results, in the same order as the input
            deliveries.add( result );
        }
        // build a new object with the overall result
        JsonObject reply = new JsonObject();
        reply.putNumber( "multicast_id", multicastId );
        reply.putNumber( "success", success );
        reply.putNumber( "failure", failure );
        reply.putNumber( "canonical_ids", canonicalIds );
        reply.putArray( "results", deliveries );

        return reply;
    }

    private JsonObject updateStatus( JsonObject notif, HashMap<String, JsonObject> response, JsonObject multicastResult ) {
        JsonArray returned = multicastResult.getArray( "results" );
        JsonArray regIds = notif.getArray( "registration_ids" );
        // should never happen, unless there is a flaw in gcm algorithm
        if ( returned.size() != regIds.size() ) {
            throw new RuntimeException( "Internal error: sizes do not match. regIds: " + regIds.size() + "; returned: " + returned.size() );
        }

        JsonArray reTryRegIds = new JsonArray();
        for ( int i = returned.size() - 1; i >= 0; i-- ) {
            response.put( ( String ) regIds.get( i ), ( JsonObject ) returned.get( i ) );
            boolean resend = doResubmit( ( JsonObject ) returned.get( i ) );
            if ( resend ) {
                reTryRegIds.addString( ( String ) regIds.get( i ) );
            }
        }
        notif.putArray( "registration_ids", reTryRegIds );

        return notif;
    }

    private boolean doResubmit( JsonObject entry ) {
        return voidNull( entry.getString( "error" ) ).equalsIgnoreCase( "Unavailable" );
    }

    private void send( Message<JsonObject> message, JsonObject notif, String apiKey ) {
        logger.debug( "Sending POST to: " + gcm_url + " port:" + gcm_port + " with body: " + notif );

        HttpClient client = vertx.createHttpClient()
                .setHost( uri.getHost() )
                .setPort( gcm_port );

        if ( gcm_url.toLowerCase().startsWith( "https" ) ) {
            client = client.setSSL( true ).setTrustAll( true );
        }

        ResponseHelper helper = new ResponseHelper( gcm_min_backoff_delay );

        submitGCM( helper, notif, apiKey, client, message, 0 );

    }

    private void submitGCM( final ResponseHelper helper, final JsonObject notif, final String apiKey, final HttpClient client, final Message<JsonObject> message, final int attempt ) {

        final Buffer toSend;
        try {
            toSend = new Buffer( notif.encode().getBytes( "UTF-8" ) );
        }
        catch ( UnsupportedEncodingException e ) {
            logger.error( e.getMessage() );
            return;
        }
        logger.debug( "Attempt #" + ( attempt + 1 ) + " to send notification to regIds " + notif.getArray( "registration_ids" ).encode() );
        HttpClientRequest request = client.post( uri.getPath(), new Handler<HttpClientResponse>() {
            @Override
            public void handle( final HttpClientResponse resp ) {
                final Buffer body = new Buffer( 0 );
                resp.dataHandler( new Handler<Buffer>() {
                    @Override
                    public void handle( Buffer data ) {
                        body.appendBuffer( data );
                    }
                } );
                resp.endHandler( new VoidHandler() {
                    @Override
                    public void handle() {
                        boolean tryAgain = false;
                        JsonObject[] reply = { null };
                        JsonObject newNotif = new JsonObject();
                        int status = resp.statusCode();
                        if ( status == 200 ) {
                            logger.debug( "GCM response: " + body );
                            reply[ 0 ] = new JsonObject( new String( body.getBytes() ) );
                        }
                        else {
                            logger.error( "GCM error response: " + body );
                        }
                        if ( reply[ 0 ] != null ) {
                            helper.setMulticastId( reply[ 0 ].getLong( "multicast_id" ) == null ? 0 : reply[ 0 ].getLong( "multicast_id" ) );
                            newNotif = updateStatus( notif, helper.getResponse(), reply[ 0 ] );
                            tryAgain = newNotif.getArray( "registration_ids" ).size() != 0 && attempt < gcm_backoff_retries;
                        }
                        else {
                            tryAgain = attempt < gcm_backoff_retries;
                        }
                        if ( tryAgain ) {
                            int sleepTime = helper.getBackoff() / 2 + helper.getRandom().nextInt( helper.getBackoff() );
                            try {
                                Thread.sleep( sleepTime );
                            }
                            catch ( InterruptedException ie ) {
                            }
                            if ( 2 * helper.getBackoff() < gcm_max_backoff_delay ) {
                                helper.setBackoff( helper.getBackoff() * 2 );
                            }
                            submitGCM( helper, newNotif, apiKey, client, message, attempt + 1 );
                        }
                        else {
                            if ( helper.getResponse().isEmpty() ) {
                                // all JSON posts failed due to GCM unavailability
                                sendError( message, "GCM is unavailable" );
                            }
                            else {
                                JsonObject sendBack = calculateSummary( message.body().getObject( "notification" ).getArray( "registration_ids" ), helper.getResponse(), helper.getMulticastId() );
                                sendOK( message, sendBack );
                            }
                        }
                    }
                } );
            }
        } ).putHeader( "Content-Type", "application/json" )
                .putHeader( "Content-Length", String.valueOf( toSend.length() ) )
                .putHeader( "Authorization", "key=" + apiKey )
                .write( toSend );

        request.end();
    }

    private String voidNull( String s ) {
        return s == null ? "" : s;
    }
}
