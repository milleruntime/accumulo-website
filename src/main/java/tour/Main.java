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
import org.apache.accumulo.core.client.impl.TableOperationsImpl;
import org.apache.accumulo.core.client.impl.Tables;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        exercise(conn, args);
        //mac.stop();
    }

    public static int ALL_GOOD = -1;

    private static void exercise(Connector conn, String[] args) throws Exception{
        if (args == null || args.length == 0 || args[0].isEmpty()) {
            throw new Exception("The first argument must be number of threads");
        }
        int number = Integer.parseInt(args[0]);

        System.out.println("Creating "+number+" tables over threads");
        FutureTask<Integer> [] makers = new FutureTask[number];
        FutureTask<Integer> [] testers = new FutureTask[number];
        for (int i = 0; i < number; i++) {
            final int num = i;
            FutureTask<Integer> tableMaker = new FutureTask<>(() -> {
                String name = Thread.currentThread().getName();
                try {
                    conn.tableOperations().create("GothamPD" + num);
                } catch (Exception e) {
                    System.out.println("DUDE error in thread " + name);
                    e.printStackTrace();
                    return num;
                }
                return ALL_GOOD;
            });
            makers[i] = tableMaker;

            FutureTask<Integer> tester =  new FutureTask<>(() -> {
                    if (!conn.tableOperations().exists("GothamPD" + num))
                        return num;
                    else
                        return ALL_GOOD;

            });
            testers[i] = tester;
        }

        System.out.println("starting all maker threads!!");
        ExecutorService executor = Executors.newFixedThreadPool(number * 2);
        for (int i = 0; i < number; i++)
            executor.execute(makers[i]);

        System.out.println("wait a few seconds for tables to get creating");
        Thread.sleep(5000);
        System.out.println("starting all tester threads!!");
        for (int i = 0; i < number; i++)
            executor.execute(testers[i]);

        int mCount = 0, tCount = 0;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < number; i++) {
            Integer makerResult = makers[i].get();
            if (makerResult != ALL_GOOD){
                mCount++;
            }
            Integer testerResult = testers[i].get();
            if (testerResult != ALL_GOOD){
                builder.append(testerResult).append("-");
                tCount++;
            }
        }

        System.out.println("TOTAL number of failed creates = " + mCount);
        System.out.println("TOTAL number of failed test exists = " + tCount);
        System.out.println("Fails= " + builder.toString());

        System.out.println("Clearing cache");
        Tables.clearCache(conn.getInstance());

        Thread.sleep(5000);
        System.out.println("running all tester threads again");
        for (int i = 0; i < number; i++)
            executor.execute(testers[i]);

        tCount = 0;
        for (int i = 0; i < number; i++) {
            Integer testerResult = testers[i].get();
            if (testerResult != ALL_GOOD){
                tCount++;
            }
        }

        System.out.println("TOTAL number of failed test exists = " + tCount);
        List left = executor.shutdownNow();
        System.out.println("Shutting down with remaing threads running = " + left.size());

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
        /*try(BatchScanner batchScanner = conn.createBatchScanner("GothamPD", Authorizations.EMPTY, 5000)) {
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
        }*/
    }
}
