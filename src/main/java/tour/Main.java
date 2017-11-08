package tour;

// Classes you will use along the tour
import java.util.Map;
import java.util.Random;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.minicluster.MiniAccumuloCluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("Running the Accumulo tour. Having fun yet?");

        Path tempDir = Files.createTempDirectory(Paths.get("target"), "mac");
        MiniAccumuloCluster mac = new MiniAccumuloCluster(tempDir.toFile(), "tourguide");

        mac.start();
        exercise(mac);
        mac.stop();
    }

    private static void exercise(MiniAccumuloCluster mac) throws Exception{
        // start writing your code here
        // Connect to Mini Accumulo as the root user and create a table called "GothamPD".
        Connector conn = mac.getConnector("root", "tourguide");
        conn.tableOperations().create("GothamPD");

        //
        try(BatchWriter writer = conn.createBatchWriter("GothamPD", new BatchWriterConfig())) {
            for(int i = 0; i < 10_000; i++) {
                Mutation m = new Mutation(String.format("id%04d", i));
                String cf = String.format("henchmanType%d", (i % 5));
                m.put(cf, "alias", "henchman" + i);
                m.put(cf, "yearsOfService", "" + (new Random().nextInt(50)));
                m.put(cf, "wearsCape?", "false");
                writer.addMutation(m);
            }
        }

        //
        try(BatchScanner scan = conn.createBatchScanner("GothamPD", Authorizations.EMPTY, 2) {
            scan.setRange(new Range("id6999", "id7001"));
            System.out.println("Gotham Police Department Persons of Interest:");
            Long read = 0L;
            Long start = System.nanoTime();
            for (Map.Entry<Key, Value> entry : scan) {
                System.out.println("Key:" + entry.getKey());
                System.out.println("Value:" + entry.getValue());
            }
            System.out.println("Range took " + (System.nanoTime() - start)/1000.0 + " to read " + read);
        }
    }
}
