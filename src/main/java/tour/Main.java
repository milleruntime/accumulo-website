package tour;

// Classes you will use along the tour
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Column;
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
import org.apache.hadoop.io.Text;

public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("Running the Accumulo tour. Having fun yet?");

        //Path tempDir = Files.createTempDirectory(Paths.get("target"), "mac");
        //MiniAccumuloCluster mac = new MiniAccumuloCluster(tempDir.toFile(), "tourguide");

        String instanceName = "uno";
        String zooServers = "localhost";
        Instance inst = new ZooKeeperInstance(instanceName, zooServers);

        Connector conn = inst.getConnector("root", new PasswordToken("secret"));

        //mac.start();
        exercise(conn);
        //mac.stop();
    }

    private static void exercise(Connector conn) throws Exception{
        // start writing your code here
        // Connect to Mini Accumulo as the root user and create a table called "GothamPD".
        //Connector conn = mac.getConnector("root", "tourguide");


        /* Generate 10,000 rows of henchman data
        conn.tableOperations().create("GothamPD");
        try(BatchWriter writer = conn.createBatchWriter("GothamPD", new BatchWriterConfig())) {
            for(int i = 0; i < 10_000; i++) {
                Mutation m = new Mutation(String.format("id%04d", i));
                m.put("villain", "alias", "henchman" + i);
                m.put("villain", "yearsOfService", "" + (new Random().nextInt(50)));
                m.put("villain", "wearsCape?", "false");
                writer.addMutation(m);
            }
        }*/

        // 1. Create a BatchScanner with 5 query threads
        try(BatchScanner batchScanner = conn.createBatchScanner("GothamPD", Authorizations.EMPTY, 5000)) {
            // 2. Create a collection of 2 sample ranges and set it to the batchScanner
            List ranges = new ArrayList<Range>();
            ranges.add(new Range("id9000", "id9999"));
            ranges.add(new Range("id1000", "id1999"));
            batchScanner.setRanges(ranges);

            // 3. Fetch just the columns we want
            batchScanner.fetchColumn(new Text("villain"), new Text("yearsOfService"));

            // 4. Calculate average years of service
            Long totalYears = 0L;
            Long entriesRead = 0L;
            System.out.println("Starting to iterate");
            Text firstRow = null, lastRow = null;
            for (Map.Entry<Key, Value> entry : batchScanner) {
                if(firstRow == null)
                    firstRow = entry.getKey().getRow();
                totalYears += Long.valueOf(entry.getValue().toString());
                entriesRead++;
                lastRow = entry.getKey().getRow();
            }
            System.out.println("Finished with firstrow=" + firstRow + " lastrow=" + lastRow);
            System.out.println("Out of " + entriesRead + " entries, average years of a henchman: " + totalYears / entriesRead);
        }
    }
}
