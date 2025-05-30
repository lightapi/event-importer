package net.lightapi.importer;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.networknt.config.Config;
import com.networknt.config.JsonMapper;
import com.networknt.kafka.common.AvroSerializer;
import com.networknt.kafka.common.KafkaProducerConfig;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.kafka.clients.producer.*;
import tech.allegro.schema.json2avro.converter.JsonAvroConverter;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import static java.io.File.separator;

/**
 * A Cli to export event from Kafka.
 *
 * @author Steve Hu
 */
public class Cli {

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
            cli.run(jCommander);
        } catch (ParameterException e)
        {
            System.out.println("Command line parameter error: " + e.getLocalizedMessage());
            e.usage();
        }
    }

    public void run(JCommander jCommander) throws Exception {
        if (help) {
            jCommander.usage();
            return;
        }
        KafkaProducerConfig config = (KafkaProducerConfig) Config.getInstance().getJsonObjectConfig(KafkaProducerConfig.CONFIG_NAME, KafkaProducerConfig.class);
        System.out.println("props = " + JsonMapper.toJson(config.getProperties()));
        KafkaProducer<byte[], byte[]> producer = new KafkaProducer <> (config.getProperties());
        ImportCallback callback = new ImportCallback();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            while(true) {
                String line = null;
                try {
                    line = reader.readLine();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if(line == null) break;
                if(line.startsWith("#")) continue;  // skip comments.
                int first = line.indexOf(" ");
                String key = line.substring(0, first);
                String value = line.substring(first + 1);
                System.out.println("Importing record key = " + key + " value = " + value);
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                ProducerRecord <byte[], byte[]> data = new ProducerRecord<>(config.getTopic(), key.getBytes(StandardCharsets.UTF_8), bytes);
                producer.send(data, callback);
                System.out.println("Imported record key: " + key + " with event " + value);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        producer.flush();
        producer.close();
        System.out.println("All Portal Events have been imported successfully from " + filename + ". Have fun!!!");
    }

    private static class ImportCallback implements Callback {
        @Override
        public void onCompletion(RecordMetadata recordMetadata, Exception e) {
            if (e != null) {
                System.out.println("Error while importing message to topic :" + recordMetadata);
                e.printStackTrace();
            } else {
                String message = String.format("Import message to topic:%s partition:%s  offset:%s", recordMetadata.topic(), recordMetadata.partition(), recordMetadata.offset());
                System.out.println(message);
            }
        }
    }
}
