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

package net.atarno.vertx.gcm.tests;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Verticle;

/**
 * @author <a href="mailto:atarno@gmail.com">Asher Tarnopolski</a>
 *         <p/>
 */
public class GCMTestClient extends Verticle {
    String address;

    @Override
    public void start() {
        EventBus eb = vertx.eventBus();
        address = "test.vertx.gcm";

        JsonObject config = new JsonObject();
        config.putString( "address", address );
        config.putNumber( "gcm_registration_ids_limit", 1000 );//gcm default
        config.putNumber( "gcm_max_seconds_to_leave", 2419200 );//gcm default
        config.putNumber( "gcm_backoff_retries", 5 );
        config.putString( "gcm_url", "https://android.googleapis.com/gcm/send" );


        container.deployWorkerVerticle( "net.atarno.vertx.gcm.server.GCMServer", config, 1, false, new Handler<AsyncResult<String>>() {
            @Override
            public void handle( AsyncResult<String> stringAsyncResult ) {
                testValidNotification();
            }
        } );
    }

    @Override
    public void stop() {
        super.stop();
    }

    public void testValidNotification() {
        JsonObject notif = new JsonObject();
        notif.putString( "api_key", "key0" );

        JsonObject data = new JsonObject();
        data.putString( "action", "TEXT" );
        data.putString( "sender", "vertx-gcm" );
        data.putString( "message_title", "Test * Test * Test" );
        data.putString( "message_text", "Hello world" );

        JsonObject n = new JsonObject();
        n.putString( "collapse_key", "key" );
        n.putNumber( "time_to_live", 60 * 10 );
        n.putBoolean( "delay_while_idle", false );
        //n.putBoolean( "dry_run", true );
        //n.putString("restricted_package_name", "");
        n.putObject( "data", data );
        n.putArray( "registration_ids", new JsonArray(
                new String[]{ "token0",
                        "token1",
                        "token2" } ) );

        notif.putObject( "notification", n );
        push( notif );
    }

    private void push( JsonObject notif ) {
        Handler<Message<JsonObject>> replyHandler = new Handler<Message<JsonObject>>() {
            public void handle( Message<JsonObject> message ) {
                System.out.println( "received: \n" + message.body().encode() );
            }
        };
        vertx.eventBus().send( address, notif, replyHandler );
    }
}
