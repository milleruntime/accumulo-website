package tour;

// Classes you will use along the tour
import java.util.Map;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
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
        // 1. Connect to Mini Accumulo as the root user and create a table called "GothamPD".
        Connector conn = mac.getConnector("root", "tourguide");
        conn.tableOperations().create("GothamPD");

        // 2. Create a Mutation object to hold all changes to a row in a table.  Each row has a unique row ID.
        Mutation mutation1 = new Mutation("id0001");
        Mutation mutation2 = new Mutation("id0002");
        Mutation mutation3 = new Mutation("id0003");

        // 3. Create key/value pairs for Batman.  Put them in the "hero" family.
        mutation1.put("hero","alias", "Batman");
        mutation1.put("hero","name", "Bruce Wayne");
        mutation1.put("hero","wearsCape?", "true");
        mutation2.put("hero","alias", "Robin");
        mutation2.put("hero","name", "Dick Grayson");
        mutation2.put("hero","wearsCape?", "true");
        mutation3.put("villain","alias", "Joker");
        mutation3.put("villain","name", "Unknown");
        mutation3.put("villain","wearsCape?", "false");

        // 4. Create a BatchWriter to the GothamPD table and add your mutation to it.  Try w/ resources will close for us.
        try(BatchWriter writer = conn.createBatchWriter("GothamPD", new BatchWriterConfig())) {
            writer.addMutation(mutation1);
            writer.addMutation(mutation2);
            writer.addMutation(mutation3);
        }

        // 5. Read and print all rows of the "GothamPD" table. Try w/ resources will close for us.
        try(Scanner scan = conn.createScanner("GothamPD", Authorizations.EMPTY)) {
            System.out.println("Gotham Police Department Persons of Interest:");
            // A Scanner is an extension of java.lang.Iterable so behaves just like one.
            for (Map.Entry<Key, Value> entry : scan) {
                System.out.println("Key:" + entry.getKey());
                System.out.println("Value:" + entry.getValue());
            }
        }
    }
}
