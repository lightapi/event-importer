package net.lightapi.importer;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.networknt.config.Config;
import com.networknt.db.provider.DbProvider;
import com.networknt.db.provider.SqlDbStartupHook;
import com.networknt.monad.Result;
import com.networknt.service.SingletonServiceFactory;
import com.networknt.utility.Constants;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import net.lightapi.portal.EventTypeUtil;
import net.lightapi.portal.PortalConstants;
import net.lightapi.portal.db.PortalDbProvider;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A Cli to export event from Kafka.
 *
 * @author Steve Hu
 */
public class Cli {
    public static PortalDbProvider dbProvider;
    public static SqlDbStartupHook sqlDbStartupHook;

    @Parameter(names={"--filename", "-f"}, required = false,
            description = "The filename to be imported.")
    String filename;

    @Parameter(names={"--help", "-h"}, help = true)
    private boolean help;

    public static void main(String ... argv) throws Exception {
        try {
            Cli cli = new Cli();
            JCommander jCommander = JCommander.newBuilder()
                    .addObject(cli)
                    .build();
            jCommander.parse(argv);
            sqlDbStartupHook = new SqlDbStartupHook();
            sqlDbStartupHook.onStartup();
            dbProvider = (PortalDbProvider) SingletonServiceFactory.getBean(DbProvider.class);
            cli.run(jCommander);

        } catch (ParameterException e) {
            System.out.println("Command line parameter error: " + e.getLocalizedMessage());
            e.usage();
        }
    }

    public void run(JCommander jCommander) throws Exception {
        if (help) {
            jCommander.usage();
            return;
        }
        // Resolve the JSON event format
        EventFormat format = EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);
        List<CloudEvent> currentBatch = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            while(true) {
                String line = null;
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if(line == null) break;
                if(line.trim().isEmpty()) continue; // skip empty lines.
                if(line.startsWith("#")) continue;  // skip comments.
                System.out.println("Importing record value = " + line);
                // insert into database tables with JDBC
                assert format != null;
                CloudEvent cloudEvent = format.deserialize(line.getBytes());
                if(cloudEvent.getExtension(PortalConstants.EVENT_AGGREGATE_VERSION) == null) {
                    cloudEvent = CloudEventBuilder.v1(cloudEvent)
                            .withExtension(PortalConstants.EVENT_AGGREGATE_VERSION, 1L)
                            .build();
                }
                if(cloudEvent.getExtension(PortalConstants.AGGREGATE_TYPE) == null) {
                    cloudEvent = CloudEventBuilder.v1(cloudEvent)
                            .withExtension(PortalConstants.AGGREGATE_TYPE, EventTypeUtil.deriveAggregateTypeFromEventType(cloudEvent.getType()))
                            .build();
                }
                byte[] dataBytes = Objects.requireNonNull(cloudEvent.getData()).toBytes();
                Map<String, Object> dataMap = Config.getInstance().getMapper().readValue(dataBytes, Map.class);
                if(cloudEvent.getSubject() == null) {
                    cloudEvent = CloudEventBuilder.v1(cloudEvent)
                            .withSubject(EventTypeUtil.getAggregateId(cloudEvent.getType(), dataMap))
                            .build();
                }
                String user = (String) cloudEvent.getExtension(Constants.USER);
                Number nonce = (Number) cloudEvent.getExtension(PortalConstants.NONCE);
                System.out.println("Importing record with user = " + user + " and original nonce = " + nonce);
                Result<Long> nonceResult = dbProvider.queryNonceByUserId(user);
                if(nonceResult.isFailure()) {
                    System.out.println("Failed to query nonce for user: " + user + " error: " + nonceResult.getError());
                    return;
                }
                long newNonce = nonceResult.getResult() + 1;
                System.out.println("New nonce for user " + user + " is " + newNonce);
                cloudEvent = CloudEventBuilder.v1(cloudEvent)
                        .withExtension(PortalConstants.NONCE, newNonce)
                        .build();
                // Add to current batch.
                currentBatch.add(cloudEvent);

            }
            processBatch(currentBatch);

        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("All Portal Events have been imported successfully from " + filename + ". Have fun!!!");
    }

    /**
     * Processes a batch of CloudEvents by inserting them into the database in a single transaction.
     * @param batch The list of CloudEvents to insert.
     */
    private void processBatch(List<CloudEvent> batch) {
        Result<String> eventStoreResult = dbProvider.insertEventStore(batch.toArray(new CloudEvent[0]));
        if(eventStoreResult.isFailure()) {
            System.out.println("Failed to insert event store: " + eventStoreResult.getError());
        } else {
            System.out.println("Imported the batch successfully.");
        }
    }
}
