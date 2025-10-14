package net.lightapi.importer;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.networknt.config.Config;
import com.networknt.config.JsonMapper;
import com.networknt.db.provider.DbProvider;
import com.networknt.db.provider.SqlDbStartupHook;
import com.networknt.monad.Result;
import com.networknt.service.SingletonServiceFactory;
import com.networknt.status.Status;
import com.networknt.utility.Constants;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import net.lightapi.portal.EventMutator;
import net.lightapi.portal.EventTypeUtil;
import net.lightapi.portal.PortalConstants;
import net.lightapi.portal.db.PortalDbProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * A Cli to export event from Kafka.
 *
 * @author Steve Hu
 */
public class Cli {
    private static final Logger logger = LoggerFactory.getLogger(Cli.class);
    public static PortalDbProvider dbProvider;
    public static SqlDbStartupHook sqlDbStartupHook;

    @Parameter(names={"--filename", "-f"}, required = false,
            description = "The filename to be imported.")
    String filename;

    @Parameter(names={"--replacement", "-r"}, required = false,
            description = "The replacement to be imported.")
    String replacement;

    @Parameter(names={"--enrichment", "-e"}, required = false,
            description = "The enrichment to be imported.")
    String enrichment;

    @Parameter(names={"--help", "-h"}, help = true)
    private boolean help;

    private static String readFileContent(String filename) throws IOException {
        // Reads the entire file into a string. Best for JSON array format.
        return Files.readString(Paths.get(filename), java.nio.charset.StandardCharsets.UTF_8);
    }

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
        } catch (Exception e) {
            System.err.println("An unexpected error occurred during import: " + e.getLocalizedMessage());
            e.printStackTrace();
        }
    }

    public void run(JCommander jCommander) throws Exception {
        if (help) {
            jCommander.usage();
            return;
        }

        logger.info("Starting event import with filename: {}", filename);
        if (replacement != null) logger.info("Replacement rules: {}", replacement);
        if (enrichment != null) logger.info("Enrichment rules: {}", enrichment);

        // Resolve the JSON event format
        EventFormat format = EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);
        if (format == null) {
            logger.error("No CloudEvent JSON format provider found.");
            throw new IllegalStateException("CloudEvent JSON format not found.");
        }

        // --- Instantiate EventMutator ---
        EventMutator mutator = new EventMutator(replacement, enrichment);

        List<CloudEvent> finalBatch = new ArrayList<>();
        long importedCount = 0;

        // --- NEW: Read entire file content ---
        String fileContent = readFileContent(filename);

        // --- NEW: Parse content as JSON Array ---
        List<Map<String, Object>> rawEventMaps;
        try {
            // Use TypeReference for List<Map<String, Object>> to parse the JSON array
            rawEventMaps = Config.getInstance().getMapper().readValue(fileContent, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            logger.error("Failed to parse file content as a JSON array of events.", e);
            throw new IllegalArgumentException("Input file is not a valid JSON array or list of objects.", e);
        }

        // --- Process each event in the parsed list ---
        for (Map<String, Object> rawEventMap : rawEventMaps) {
            String eventJson = null; // Defined outside try for logging purposes
            try {
                // 1. Convert Map to JSON string for the mutator/deserializer
                eventJson = JsonMapper.toJson(rawEventMap);

                // 2. Perform Mutation/Enrichment (returns mutated JSON string)
                if(logger.isTraceEnabled()) logger.trace("original eventJson = {}", eventJson);
                String mutatedEventJson = mutator.mutate(eventJson);
                if(logger.isTraceEnabled()) logger.trace("mutated eventJson = {}", mutatedEventJson);

                // 3. Deserialize the Mutated JSON into a CloudEvent
                CloudEvent cloudEvent = format.deserialize(mutatedEventJson.getBytes(StandardCharsets.UTF_8));

                // --- Enrichment/Validation (Transferred from original code) ---
                // This section checks and enforces critical extensions (version, type, subject)

                // 4. Set AggregateVersion (Handles String to Long conversion and null check)
                if(cloudEvent.getExtension(PortalConstants.EVENT_AGGREGATE_VERSION) != null) {
                    // make sure that the type is integer instead of string.
                    Object av = cloudEvent.getExtension(PortalConstants.EVENT_AGGREGATE_VERSION);
                    if(av instanceof String) {
                        cloudEvent = CloudEventBuilder.v1(cloudEvent).withExtension(PortalConstants.EVENT_AGGREGATE_VERSION, Integer.parseInt((String)av)).build();
                    }
                } else {
                    if(rawEventMap.containsKey("aggregateVersion") && rawEventMap.get("aggregateVersion") instanceof Number) {
                        cloudEvent = CloudEventBuilder.v1(cloudEvent).withExtension(PortalConstants.EVENT_AGGREGATE_VERSION, ((Number)rawEventMap.get("aggregateVersion"))).build();
                    } else {
                        cloudEvent = CloudEventBuilder.v1(cloudEvent).withExtension(PortalConstants.EVENT_AGGREGATE_VERSION, 1).build();
                    }
                }

                // 5. Set AggregateType if missing
                if(cloudEvent.getExtension(PortalConstants.AGGREGATE_TYPE) == null) {
                    cloudEvent = CloudEventBuilder.v1(cloudEvent)
                            .withExtension(PortalConstants.AGGREGATE_TYPE, EventTypeUtil.deriveAggregateTypeFromEventType(cloudEvent.getType()))
                            .build();
                }

                // 6. Set Subject (Aggregate ID) if missing
                if(cloudEvent.getSubject() == null) {
                    Map<String, Object> dataMap = Config.getInstance().getMapper().readValue(Objects.requireNonNull(cloudEvent.getData()).toBytes(), Map.class);
                    cloudEvent = CloudEventBuilder.v1(cloudEvent)
                            .withSubject(EventTypeUtil.getAggregateId(cloudEvent.getType(), dataMap))
                            .build();
                }

                // 7. Nonce Calculation (CRITICAL: DB Query per event - still necessary for correctness)
                String user = (String) cloudEvent.getExtension(Constants.USER);
                if (user == null) throw new IllegalArgumentException("CloudEvent missing required 'user' extension for Nonce calculation.");

                Result<Long> nonceResult = dbProvider.queryNonceByUserId(user);
                long newNonce = 1L;
                if(nonceResult.isFailure()) {
                    Status status = nonceResult.getError();
                    if(status.getStatusCode() == 404) {
                        logger.info("Nonce is not found for user: {}. Using 1L as this is the first time this user is used.", user);
                    } else {
                        throw new RuntimeException("Failed to query nonce for user: " + user + " error: " + nonceResult.getError());
                    }
                } else {
                    newNonce = nonceResult.getResult() + 1;
                }

                cloudEvent = CloudEventBuilder.v1(cloudEvent).withExtension(PortalConstants.NONCE, (int)newNonce).build();

                // 8. Add to current batch.
                finalBatch.add(cloudEvent);

            } catch (Exception e) {
                logger.error("Error processing event: {}. Cause: {}", eventJson, e.getMessage(), e);
                // Log the error and continue to the next event in the array.
                // The failed event is simply skipped from the finalBatch.
            }
        }

        // --- Process the entire final batch ---
        // The whole file content is now treated as one logical batch for insertion.
        processBatch(finalBatch);

        importedCount = finalBatch.size(); // The count of successfully processed and batched events

        logger.info("Import process finished. Total events successfully batched for import: {}", importedCount);
        System.out.println("All Portal Events have been imported successfully from " + filename + ". Have fun!!!");
    }

    /**
     * Processes a batch of CloudEvents by inserting them into the database in a single transaction.
     * @param batch The list of CloudEvents to insert.
     */
    private void processBatch(List<CloudEvent> batch) {
        if (batch.isEmpty()) {
            return;
        }

        // This method will rely on dbProvider.insertEventStore to handle the actual JDBC transaction
        // (getConnection, setAutoCommit(false), executeBatch, commit/rollback, closeConnection)
        Result<String> eventStoreResult = dbProvider.insertEventStore(batch.toArray(new CloudEvent[0]));

        if(eventStoreResult.isFailure()) {
            logger.error("Failed to insert batch of {} events. Rollback occurred. Error: {}", batch.size(), eventStoreResult.getError());
            // Since this is the CLI, we re-throw a RuntimeException to signal a critical failure.
            throw new RuntimeException("Failed to insert event store batch: " + eventStoreResult.getError().getMessage());
        } else {
            logger.info("Imported batch of {} records successfully.", batch.size());
        }
    }
}
