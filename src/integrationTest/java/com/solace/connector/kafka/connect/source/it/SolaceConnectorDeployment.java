package com.solace.connector.kafka.connect.source.it;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.io.FileUtils;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SolaceConnectorDeployment implements TestConstants {

  static Logger logger = LoggerFactory.getLogger(SolaceConnectorDeployment.class.getName());

  static String kafkaTestTopic = KAFKA_SOURCE_TOPIC + "-" + Instant.now().getEpochSecond();
  OkHttpClient client = new OkHttpClient();
  String connectorAddress = new TestConfigProperties().getProperty("kafka.connect_rest_url");

  public void waitForConnectorRestIFUp() {
    Request request = new Request.Builder().url("http://" + connectorAddress + "/connector-plugins").build();
    Response response = null;
    do {
      try {
        Thread.sleep(1000l);
        response = client.newCall(request).execute();
      } catch (IOException | InterruptedException e) {
        // Continue looping
      }
    } while (response == null || !response.isSuccessful());
  }

  public void provisionKafkaTestTopic() {
    // Create a new kafka test topic to use
    String bootstrapServers = new TestConfigProperties().getProperty("kafka.bootstrap_servers");
    Properties properties = new Properties();
    properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    AdminClient adminClient = AdminClient.create(properties);
    NewTopic newTopic = new NewTopic(kafkaTestTopic, 5, (short) 1); // new NewTopic(topicName, numPartitions,
                                                                    // replicationFactor)
    List<NewTopic> newTopics = new ArrayList<NewTopic>();
    newTopics.add(newTopic);
    adminClient.createTopics(newTopics);
    adminClient.close();
  }

  void startConnector() {
    startConnector(null); // Defaults only, no override
  }

  void startConnector(Properties props) {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    String configJson = null;
    // Prep config files
    try {
      // Configure .json connector params
      File jsonFile = new File(
          UNZIPPEDCONNECTORDESTINATION + "/" + Tools.getUnzippedConnectorDirName() + "/" + CONNECTORJSONPROPERTIESFILE);
      String jsonString = FileUtils.readFileToString(jsonFile);
      JsonElement jtree = new JsonParser().parse(jsonString);
      JsonElement jconfig = jtree.getAsJsonObject().get("config");
      JsonObject jobject = jconfig.getAsJsonObject();
      // Set properties defaults
      jobject.addProperty("sol.host", "tcp://" + new TestConfigProperties().getProperty("sol.host") + ":55555");
      jobject.addProperty("sol.username", SOL_ADMINUSER_NAME);
      jobject.addProperty("sol.password", SOL_ADMINUSER_PW);
      jobject.addProperty("sol.vpn_name", SOL_VPN);
      jobject.addProperty("kafka.topic", kafkaTestTopic);
      jobject.addProperty("sol.topics", SOL_TOPICS);
      jobject.addProperty("sol.queue", SOL_QUEUE);
      jobject.addProperty("sol.message_processor_class", CONN_MSGPROC_CLASS);
      jobject.addProperty("sol.kafka_message_key", CONN_KAFKA_MSGKEY);
      jobject.addProperty("value.converter", "org.apache.kafka.connect.converters.ByteArrayConverter");
      jobject.addProperty("key.converter", "org.apache.kafka.connect.storage.StringConverter");
      jobject.addProperty("tasks.max", "1");
      // Override properties if provided
      if (props != null) {
        props.forEach((key, value) -> {
          jobject.addProperty((String) key, (String) value);
        });
      }
      configJson = gson.toJson(jtree);
    } catch (IOException e) {
      e.printStackTrace();
    }

    // Configure and start the solace connector
    try {
      // check presence of Solace plugin: curl
      // http://18.218.82.209:8083/connector-plugins | jq
      Request request = new Request.Builder().url("http://" + connectorAddress + "/connector-plugins").build();
      try (Response response = client.newCall(request).execute()) {
        assertTrue(response.isSuccessful());
        ResponseBody responseBody = response.body();
        assertNotNull(responseBody);
        String results = responseBody.string();
        logger.info("Available connector plugins: " + results);
        assertThat(results, containsString("solace"));
      }

      // Delete a running connector, if any
      Request deleterequest = new Request.Builder().url("http://" + connectorAddress + "/connectors/solaceSourceConnector")
          .delete().build();
      try (Response deleteresponse = client.newCall(deleterequest).execute()) {
        logger.info("Delete response: " + deleteresponse);
      }

      // configure plugin: curl -X POST -H "Content-Type: application/json" -d
      // @solace_source_properties.json http://18.218.82.209:8083/connectors
      Request configrequest = new Request.Builder().url("http://" + connectorAddress + "/connectors")
          .post(RequestBody.create(configJson, MediaType.parse("application/json"))).build();
      try (ResponseBody configresponse = client.newCall(configrequest).execute().body()) {
        assertNotNull(configresponse);
        String configresults = configresponse.string();
        logger.info("Connector config results: " + configresults);
      }

      // check success
      AtomicReference<JsonObject> statusResponse = new AtomicReference<>(new JsonObject());
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
        JsonObject connectorStatus;
        do {
          connectorStatus = getConnectorStatus();
          statusResponse.set(connectorStatus);
        } while (!"RUNNING".equals(connectorStatus.getAsJsonObject("connector").get("state").getAsString()));
      }, () -> "Timed out while waiting for connector to start: " + gson.toJson(statusResponse.get()));
      Thread.sleep(10000); // Give some extra time to start
      logger.info("Connector is now RUNNING");
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  public JsonObject getConnectorStatus() {
    Request request = new Request.Builder()
            .url("http://" + connectorAddress + "/connectors/solaceSourceConnector/status").build();
    return assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
      while (true) {
        try (Response response = client.newCall(request).execute()) {
          if (!response.isSuccessful()) {
            continue;
          }

          return Optional.ofNullable(response.body())
                  .map(ResponseBody::charStream)
                  .map(s -> new JsonParser().parse(s))
                  .map(JsonElement::getAsJsonObject)
                  .orElseGet(JsonObject::new);
        }
      }
    });
  }
}
