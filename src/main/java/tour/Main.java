package tour;

// Classes you will use along the tour
import java.util.Map;
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

        // Create a "secretIdentity" authorization & visibility
        final String secId = "secretIdentity";
        Authorizations auths = new Authorizations(secId);
        ColumnVisibility visibility = new ColumnVisibility(secId);

        // Create a user with the "secretIdentity" authorization and grant him read permissions on our table
        conn.securityOperations().createLocalUser("commissioner", new PasswordToken("gordanrocks"));
        conn.securityOperations().changeUserAuthorizations("commissioner", auths);
        conn.securityOperations().grantTablePermission("commissioner", "GothamPD", TablePermission.READ);

        // Create 3 Mutation objects, securing the proper columns.
        Mutation mutation1 = new Mutation("id0001");
        mutation1.put("hero","alias", "Batman");
        mutation1.put("hero","name", visibility, "Bruce Wayne");
        mutation1.put("hero","wearsCape?", "true");
        Mutation mutation2 = new Mutation("id0002");
        mutation2.put("hero","alias", "Robin");
        mutation2.put("hero","name", visibility,"Dick Grayson");
        mutation2.put("hero","wearsCape?", "true");
        Mutation mutation3 = new Mutation("id0003");
        mutation3.put("villain","alias", "Joker");
        mutation3.put("villain","name", "Unknown");
        mutation3.put("villain","wearsCape?", "false");

        // Create a BatchWriter to the GothamPD table and add your mutations to it.
        // Once the BatchWriter is closed by the try w/ resources, data will be available to scans.
        try(BatchWriter writer = conn.createBatchWriter("GothamPD", new BatchWriterConfig())) {
            writer.addMutation(mutation1);
            writer.addMutation(mutation2);
            writer.addMutation(mutation3);
        }

        // Read and print all rows of the commissioner can see. Pass Scanner proper authorizations
        Connector commishConn = mac.getConnector("commissioner", "gordanrocks");
        try(Scanner scan = commishConn.createScanner("GothamPD", auths)) {
            System.out.println("Gotham Police Department Persons of Interest:");
            for (Map.Entry<Key, Value> entry : scan) {
                System.out.printf("Key : %-60s  Value : %s\n", entry.getKey(), entry.getValue());
            }
        }
    }
}
