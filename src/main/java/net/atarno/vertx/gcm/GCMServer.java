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

package net.atarno.vertx.gcm;

import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Random;

/**
* GCM Server busmod<p>
* Please see the manual for a full description<p>
*
* @author <a href="mailto:atarno@gmail.com">Asher Tarnopolski</a>
*
* <strong>Note: </strong>This class makes use of code published by Google.
* <p>
* <a href="https://code.google.com/p/gcm/source/browse/gcm-server/src/com/google/android/gcm/server/Sender.java?r=83d8e0238574c6b33c893d9cda3293d1dfca16c1">Source code</a>
* <p>
*
*/
public class GCMServer extends BusModBase implements Handler<Message<JsonObject>>
{ 
   private String address;
   private String gcm_url;
   private int gcm_registration_ids_limit;
   private int gcm_max_seconds_to_leave;
   private int gcm_backoff_retries;
	private int gcm_min_backoff_delay;
   private int gcm_max_backoff_delay;

   @Override
   public void start()
   {
      super.start();
      address = getOptionalStringConfig("address", "vertx.gcm");
      gcm_registration_ids_limit = getOptionalIntConfig("gcm_registration_ids_limit", 1000);//gcm default
      gcm_max_seconds_to_leave = getOptionalIntConfig("gcm_max_seconds_to_leave", 2419200);//gcm default
      gcm_backoff_retries = getOptionalIntConfig("gcm_backoff_retries", 5);
      gcm_url = getOptionalStringConfig("gcm_url", "https://android.googleapis.com/gcm/send");

      gcm_max_backoff_delay = 1024000;//gcm default
      gcm_min_backoff_delay = 1000;//gcm default

      eb.registerHandler(address, this);
      logger.debug("GCMServer worker was registered as " + address);
   }

   @Override
   public void stop()
   {
      logger.debug("GCMServer worker " + address +" was unregistered");
   }

   @SuppressWarnings("ConstantConditions")
   @Override
   public void handle(Message<JsonObject> message)
   {
      //input validation
      if(voidNull(gcm_url).isEmpty())
      {
         sendError(message, "Config parameter 'gcm_url' cannot be empty");
         return;
      }
      String apiKey = voidNull(message.body.getString("api_key"));
      if(apiKey.isEmpty())
      {
         sendError(message, "Missing mandatory field 'api_key'");
         return;
      }

      JsonObject n = message.body.getObject("notification");
      if(n == null)
      {
         sendError(message, "Missing mandatory field 'notification'");
         return;
      }

      int ttl = n.getInteger("time_to_live");
      if(ttl > gcm_max_seconds_to_leave)
      {
         sendError(message, "Max value of 'time_to_live' exceeded: " + ttl + " > " + gcm_max_seconds_to_leave);
         return;
      }

      JsonArray regIds = n.getArray("registration_ids");
      if(regIds == null || regIds.size() == 0)
      {
         sendError(message, "Missing mandatory non-empty field 'registration_ids'");
         return;
      }
      if(regIds.size() > gcm_registration_ids_limit)
      {
         sendError(message, "Max size of 'registration_ids' exceeded: " + regIds.size() +" > " + gcm_registration_ids_limit);
         return;
      }
      
      logger.debug("Ready to push notification: " + message.body.encode());

      //gcm
      JsonObject notif = n.copy();
      int attempt = 0;;
      int backoff = gcm_min_backoff_delay;
      boolean tryAgain;
      HashMap<String, JsonObject> response = new HashMap<String, JsonObject>();
      long multicastId = 0;
      Random random = new Random();
      do
      {
         JsonObject multicastResult = null;
         attempt++;
         logger.debug("Attempt #" + attempt + " to send notification to regIds " + notif.getArray("registration_ids"));
         
         try {
            multicastResult = sendNoRetry(notif, apiKey);
         }
         catch(Exception e) 
         {
            logger.error(e);
         }
         if (multicastResult != null)
         {
            if(multicastId == 0)
            {
               multicastId = multicastResult.getLong("multicast_id");
            }
            notif = updateStatus(notif, response, multicastResult);
            tryAgain = notif.getArray("registration_ids").size() != 0 && attempt <= gcm_backoff_retries;
         }
         else
         {
            tryAgain = attempt <= gcm_backoff_retries;
         }
         if (tryAgain)
         {
            int sleepTime = backoff / 2 + random.nextInt(backoff);
            try {
               Thread.sleep(sleepTime);
            }
            catch (InterruptedException ie){}
            if (2 * backoff < gcm_max_backoff_delay)
            {
               backoff *= 2;
            }
         }
      } while (tryAgain);

      if (response.isEmpty())
      {
         // all JSON posts failed due to GCM unavailability
         sendError(message, "GCM is unavailable");
         return;
      }

      JsonObject sendBack = calculateSummary(regIds, response, multicastId);

      sendOK(message, sendBack);
   }

   private JsonObject calculateSummary(JsonArray regIds, HashMap<String, JsonObject> response, long multicastId)
   {
      int success = 0;
      int failure = 0;
      int canonicalIds = 0;
      JsonArray deliveries = new JsonArray();
      for (Object regId : regIds)
      {
         JsonObject result = response.get(regId);
         if (!voidNull(result.getString("message_id")).isEmpty())
         {
            success++;
            if (!voidNull(result.getString("registration_id")).isEmpty()) 
            {
               canonicalIds++;
            }
         } 
         else 
         {
            failure++;
         }
         // add results, in the same order as the input
         deliveries.add(result);
      }
      // build a new object with the overall result
      JsonObject reply = new JsonObject();
      reply.putNumber("multicast_id", multicastId);
      reply.putNumber("success", success);
      reply.putNumber("failure", failure);
      reply.putNumber("canonical_ids", canonicalIds);
      reply.putArray("results", deliveries);

      return reply;
   }

   private JsonObject updateStatus(JsonObject notif, HashMap<String, JsonObject> response, JsonObject multicastResult)
   {
      JsonArray returned = multicastResult.getArray("results");
      JsonArray regIds = notif.getArray("registration_ids");
      // should never happen, unless there is a flaw in gcm algorithm
      if (returned.size() != regIds.size())
      {
         throw new RuntimeException("Internal error: sizes do not match. regIds: " + regIds.size() + "; returned: " + returned.size());
      }

      JsonArray reTryRegIds = new JsonArray();
      for(int i = returned.size() -1 ; i >= 0; i--)
      {
         response.put((String) regIds.get(i), (JsonObject) returned.get(i));
         if(doResubmit((JsonObject) returned.get(i)))
         {
            reTryRegIds.addString((String) regIds.get(i));         
         }
      }
      notif.putArray("registration_ids", reTryRegIds);

      return notif;
   }
   
   private boolean doResubmit(JsonObject entry)
   {
      return voidNull(entry.getString("error")).equalsIgnoreCase("Unavailable");
   }

   private JsonObject sendNoRetry(JsonObject notif, String apiKey) throws Exception
   {
      HttpURLConnection conn = post(gcm_url, "application/json", notif, apiKey);
      int status = conn.getResponseCode();
      String responseBody;
      if (status != 200)
      {
         responseBody = getString(conn.getErrorStream());
         logger.error("GCM error response: " + responseBody);
         throw new Exception("Http status" + status +": " + responseBody);
      }
      responseBody = getString(conn.getInputStream());
      logger.debug("GCM response: " + responseBody);
      JsonObject result = new JsonObject(responseBody);
      return result;
   }

   private HttpURLConnection post(String url, String contentType, JsonObject notif, String apiKey) throws Exception
   {
      logger.debug("Sending POST to " + url + " with body: " + notif);
      byte[] bytes = notif.encode().getBytes();
      HttpURLConnection conn = getConnection(url);
      conn.setDoOutput(true);
      conn.setUseCaches(false);
      conn.setFixedLengthStreamingMode(bytes.length);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", contentType);
      conn.setRequestProperty("Authorization", "key=" + apiKey);
      OutputStream out = conn.getOutputStream();
      out.write(bytes);
      out.close();
      return conn;
   }

   protected HttpURLConnection getConnection(String url) throws IOException
   {
      HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
      return conn;
   }

   private String getString(InputStream stream) throws Exception
   {
      if(stream == null)
      {
         throw new Exception(gcm_url + " returned null");
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
      StringBuilder content = new StringBuilder();
      String newLine;
      do {
         newLine = reader.readLine();
         if (newLine != null)
         {
            content.append(newLine).append('\n');
         }
      }
      while (newLine != null);
      if (content.length() > 0)
      {
         // strip last newline
         content.setLength(content.length() - 1);
      }
      return content.toString();
   }

   private String voidNull(String s)
   {
      return s == null ? "" : s;
   }
}
