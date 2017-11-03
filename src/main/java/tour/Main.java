package tour;

// Classes you will use along the tour
import java.util.Map;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
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

    static void exercise(MiniAccumuloCluster mac) throws Exception {
        // start writing your code here
        // 1. Start by connecting to Mini Accumulo as the root user and create a table called "superheroes".
        Connector conn = mac.getConnector("root", "tourguide");
        conn.tableOperations().create("superheroes");

        // 2. Create a Mutation object to hold all changes to a row in a table.  Each row has a unique row ID.
        Mutation mutation = new Mutation("hero023948092");

        // 3. Create key/value pairs for Batman.  Put them in the "hero" family.
        mutation.put("hero","alias", "Batman");
        mutation.put("hero","name", "Bruce Wayne");
        mutation.put("hero","wearsCape?", "true");

        // 4. Create a BatchWriter to the superheroes table and add your mutation to it.  Try w/ resources will close for us.
        try(BatchWriter writer = conn.createBatchWriter("superheroes", new BatchWriterConfig())) {
            writer.addMutation(mutation);
        }

        // 5. Read and print all rows of the "superheroes" table. Try w/ resources will close for us.
        try(Scanner scan = conn.createScanner("superheroes", Authorizations.EMPTY)) {
            System.out.println("superheroes table contents:");
            // A Scanner is an extension of java.lang.Iterable so behaves just like one.
            for (Map.Entry<Key, Value> entry : scan) {
                System.out.println("Key:" + entry.getKey());
                System.out.println("Value:" + entry.getValue());
            }
        }

    }
}
