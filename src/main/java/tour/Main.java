package tour;

// Classes you will use along the tour
import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.impl.ClientContext;
import org.apache.accumulo.core.client.impl.Table;
import org.apache.accumulo.core.client.impl.TabletLocator;
import org.apache.accumulo.core.client.impl.Translator;
import org.apache.accumulo.core.client.impl.Translators;
import org.apache.accumulo.core.client.rfile.RFile;
import org.apache.accumulo.core.client.rfile.RFileWriter;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.data.impl.KeyExtent;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.tabletserver.thrift.TabletClientService;
import org.apache.accumulo.core.trace.Tracer;
import org.apache.accumulo.core.util.HostAndPort;
import org.apache.accumulo.minicluster.MiniAccumuloCluster;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.accumulo.server.AccumuloServerContext;
import org.apache.accumulo.server.client.BulkImporter;
import org.apache.accumulo.server.conf.ServerConfigurationFactory;
import org.apache.accumulo.server.util.MetadataTableUtil;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.fs.Path;

public class Main {

    static File bulkDir = new File(System.getProperty("user.dir") + "/target/rfiles");

    public static void main(String[] args) throws Exception {
        System.out.println("Running the Accumulo tour. Having fun yet?");

        java.nio.file.Path macDir = Files.createTempDirectory(Paths.get("target"), "mac");
        MiniAccumuloCluster mac = new MiniAccumuloCluster(macDir.toFile(), "tourguide");
        mac.getConfig().setNumTservers(1);

        // add mac conf to classpath
        java.io.File confDir = new File(macDir.toFile(), "conf");
        System.out.println("Adding conf dir to cp: " + confDir);
        URL u = confDir.toURI().toURL();
        URLClassLoader urlClassLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();
        Class urlClass = URLClassLoader.class;
        Method method = urlClass.getDeclaredMethod("addURL", new Class[]{URL.class});
        method.setAccessible(true);
        method.invoke(urlClassLoader, new Object[]{u});

        mac.start();

        exercise(mac);
        mac.stop();
    }

    /**
     * Current BulkImport Process in Accumulo:
     * 1 - (Client) TableOperations.importDirectory(tableName, dir, failureDir)
     * 2 - (Client) DoFateOperation(TABLE_BULK_IMPORT, ...)
     * 3 - (Master) FateServiceHandler case(TABLE_BULK_IMPORT)
     *              ...Fate Repo is seeded and is executed when BulkImport.isReady()
     * 4 - (Master) BulkImport.call()... prepareBulkImport()
     *              -> MetadataTableUtil.addBulkLoadInProgressFlag()
     *                  i.e. ~blip/2/b-000005d (this is required by GC so if some files loaded get compacted, it wont delete the rf)
     *              -> move files into Accumulo directory, renaming i.e. /accumulo/tables/2/b-000004m/I000004n.rf
     * 5 - (Master) LoadFiles.call()... Uses threadpool to assign files one-at-a-time to a random tserver
     *              -> for each file call client.bulkImportFiles()
     * 6 - (Server) ClientServiceHandler.bulkImportFiles()
     * 7 - (Server) BulkImporter.importFiles()... uses threadpool to examine (map) files
     *              EXAMINE_MAP_FILES -> findOverlappingTablets() -> TabletLocatorImpl.locateTablet() <-OFFLINE will hang here
     *                  -> estimateSizes()
     *              IMPORT_MAP_FILES -> uses threadpool to create new AssignmentTasks
     *                  -> AssignmentTask -> BulkImporter.assignMapFiles(ClientContext, HostAndPort, Map) -> client.bulkImport()
     * 8 - (Client) TabletClientService.bulkImport()
     * 9 - (Server) TabletServer.bulkImport()... per tablet call Tablet.importMapFiles()
     *10 - (Server) Tablet.importMapFiles()...
     *              -> DataFileManager.importMapFiles() -> Tablet.updatePersistedTime() -> MetadataTableUtil.updateTabletDataFile()
     *                  - Sets the loaded flag & datafile "file" marker i.e.
     *                  2;row_00000333 loaded:hdfs://localhost:8020/accumulo/tables/2/b-000005d/I000005e.rf
     *                  2;row_00000333 file:hdfs://localhost:8020/accumulo/tables/2/b-000005d/I000005e.rf
     *              -> bulkImported.addAll(FileRefs)
     *     ...Finally we return to step 5 in LoadFiles and write failed file names to failures.txt
     * (Master) CompleteBulkImport (just stops the Zoo Arbitrator)
     * (Master) CopyFailed - Look at loaded flag, remove failed ones that got loaded eventually
     *                     - Moves failed files listed in failures.txt to failureDir
     * (Master) CleanUpBulkImport -> MetadataTableUtil.removeBulkLoadInProgressFlag()
     *
     * METADATA Tablet End Row = Inclusive... prevEndRow = Exclusive
     */

    static void exercise(MiniAccumuloCluster mac) throws Exception {
        //if(!testRangeChecker()) return;

        long tid = System.currentTimeMillis(); //will get generated by FATE
        String tableName = "bulk_import1";
        Connector conn = mac.getConnector("root", "tourguide");
        userAlreadyCreatedSplits(conn, tableName);

        // user input: mapping = Range -> Files
        SortedMap<Range, List<File>> mapping = createRangeFileMapping(2);
        // just look at first Range for this example
        Range firstRange = mapping.firstKey();

        // sanity check that Range provided matches with split points???
        Collection<Text> splitList = conn.tableOperations().listSplits(tableName);
        if(!splitList.contains(firstRange.getEndKey().getRow()))
            throw new Exception("End of Range ("+firstRange.getEndKey().getRow()+") doesn't fall on a split point.");

        /* DEBUG
        System.out.println("Created Mapping file for Range: " + firstRange);
        for (File f : mapping.get(firstRange)){
            System.out.println("...with rfile: " + f.getName());
        } //END DEBUG*/

        // locate tablets for Range.. will need table lock here
        Table.ID tableId = Table.ID.of(conn.tableOperations().tableIdMap().get(tableName));
        Scanner metaDataScanner = conn.createScanner(MetadataTable.NAME, Authorizations.EMPTY);
        printMetadata(metaDataScanner);
        List<Tablet> tabletList = findOverlappingTablets(tableId, firstRange.getStartKey().getRow(), firstRange.getEndKey().getRow(), metaDataScanner);

        ///* DEBUG
        System.out.println("Found "+tabletList.size()+" tablets for Range: " + firstRange);
        for(Tablet t : tabletList) {
            System.out.println("" + t);
        } //END DEBUG */

        // map range per file for fun... this shouldn't be done IRL
        //Map<Range, File> verifiedMapping = verifyRange(mapping);
        /* DEBUG
        System.out.println("Verified ranges: ");
        for(Map.Entry<Range, File> e: verifiedMapping.entrySet()) {
            System.out.println("" + e.getKey() + " -> " + e.getValue());
        } //END DEBUG */

        Map<KeyExtent, String> locations = findTabletsAndLocations(tableId, firstRange.getStartKey().getRow(), firstRange.getEndKey().getRow(), metaDataScanner);
        /* DEBUG
        System.out.println("found locations in metadata");
        for (Map.Entry<KeyExtent, String> e : locations.entrySet()) {
            System.out.println("" + e.getKey() + ":" + e.getValue());
        } //END DEBUG */

        // import files for each tablet like BulkImporter.assignMapFiles(ClientContext, HostAndPort, Map) -> client.bulkImport()
        // Client will need a Map of KeyExtent (Range) to Map of String (Hadoop file Path string) to MapFileInfo(estimatedSize)
        // client.bulkImport() will call TabletServer.bulkImport()
        HashMap<KeyExtent,Map<String,org.apache.accumulo.core.data.thrift.MapFileInfo>> files = new HashMap<>();
        for(Tablet t : tabletList) {
            HashMap<String,org.apache.accumulo.core.data.thrift.MapFileInfo> tabletFiles = new HashMap<>();
            for (File f : mapping.get(firstRange)){
                org.apache.accumulo.core.data.thrift.MapFileInfo mfi = new org.apache.accumulo.core.data.thrift.MapFileInfo(f.length());
                tabletFiles.put(f.getPath(), mfi);
            }
            files.put(t.getExtent(), tabletFiles);
        }

        AccumuloServerContext context = new AccumuloServerContext(conn.getInstance(), new ServerConfigurationFactory(conn.getInstance()));
        for (Map.Entry<KeyExtent, String> e : locations.entrySet()) {
            HostAndPort location = HostAndPort.fromString(e.getValue());
            TabletClientService.Iface client = ThriftUtil.getTServerClient(location, context, context.getConfiguration().getTimeInMillis(Property.TSERV_BULK_TIMEOUT));
            System.out.println("calling client.bulkImport() for KeyExtent " + e.getKey() + " sending " + files.size());
            client.bulkImport(Tracer.traceInfo(), context.rpcCreds(), tid, Translator.translate(files, Translators.KET), true);
        }

        //TabletServer will throw  java.lang.IllegalArgumentException: Invalid table path file:/home/mpmill4/workspace/accumulo-tour/target/rfiles/test1.rf
        //        at org.apache.accumulo.server.fs.FileRef.extractSuffix(FileRef.java:64)
        //        at org.apache.accumulo.server.fs.FileRef.<init>(FileRef.java:40)
        //        at org.apache.accumulo.tserver.TabletServer$ThriftClientHandler.bulkImport(TabletServer.java:470)
        // Since the files are not in the accumulo table dir in HDFS

        Scanner scanner = conn.createScanner(tableName, Authorizations.EMPTY);
        System.out.println("Scanning Table " + tableName);
        for (Map.Entry<Key, Value> e : scanner) {
            System.out.println("Read RowID: " + e.getKey().getRow());
        }
    }

    private static void userAlreadyCreatedSplits(Connector conn, String tableName) throws Exception {
        // splits on table should line up with endRows of files
        conn.tableOperations().create(tableName);
        SortedSet<Text> splits = new TreeSet<>();
        splits.add(new Text(rowStr(249)));
        splits.add(new Text(rowStr(500)));
        splits.add(new Text(rowStr(751)));
        splits.add(new Text(rowStr(999)));
        conn.tableOperations().addSplits(tableName, splits);
    }


    /**
     * Verify the Range provided matches the range across the files.  This will also re-map so we have one Range per file
     */
    private static Map<Range, File> verifyRange(SortedMap<Range, List<File>> mapping) throws Exception {
        SortedMap<Range, File> verifiedMapping = new TreeMap<>();
        for(Map.Entry<Range,List<File>> entry : mapping.entrySet()){
            Range outerRange = entry.getKey();
            for(File f : entry.getValue()){
                //scan rfile
                Scanner rscanner = RFile.newScanner().from(f.getPath()).withFileSystem(FileSystem.getLocal(new Configuration())).build();
                rscanner.setRange(new Range());

                Map.Entry<Key, Value> start = null;
                Map.Entry<Key, Value> end = null;
                for (Map.Entry<Key, Value> e : rscanner) {
                    if (start == null)
                        start = e;
                    end = e;
                }
                //Text startRow = start.getKey().getRow();
                //Text endRow = end.getKey().getRow();
                //System.out.println("Verify Range for " + f.getName() + " start:" + startRow + " end:" + endRow);

                // if you use a Row, endRowInclusive is set to false, otherwise Accumulo tries to normalize the range
                // and will append a null byte and set it to false anyway!
                Range readRange = new Range(start.getKey(), end.getKey());
                //Range readRange = new Range(startRow, true, endRow, false);
                //System.out.println("readRange.isEndKeyInclusive=" + readRange.isEndKeyInclusive());
                if(outOfRange(outerRange, readRange))
                    throw new Exception("Read range in files "+readRange+" that is out of provided range "+outerRange);

                verifiedMapping.put(readRange, f);
            }
        }

        return verifiedMapping;
    }

    private static boolean testRangeChecker() {
        byte[] byte0 = {0};
        Text byteKey = new Text("8");
        byteKey.append(byte0, 0, byte0.length);
        Range innerWithByte = new Range(new Key("5"), new Key(byteKey));
        Range inner = new Range(new Key("5"), new Key("6"));
        Range innerBad = new Range(new Key("0"), new Key("6"));
        Range innerBad2 = new Range(new Key("5"), new Key("9"));
        Range outer = new Range(new Key("1"), new Key("8"));

        System.out.println("inner is outOfRange = " + outOfRange(outer, inner)); //false
        System.out.println("innerByte is outOfRange = " + outOfRange(outer, innerWithByte)); //false
        System.out.println("innerBad is outOfRange = " + outOfRange(outer, innerBad)); //true
        System.out.println("innerBad2 is outOfRange = " + outOfRange(outer, innerBad2)); //true
        return true;
    }

    // returns true if inner is out of outerRange
    private static boolean outOfRange(Range outerRange, Range inner) {
        return outerRange.beforeStartKey(inner.getStartKey()) || outerRange.afterEndKey(inner.getEndKey());
    }

    private static void printMetadata(Scanner metaDataScanner) {
        System.out.println("MetadataTable:");
        for (Map.Entry<Key, Value> e : metaDataScanner) {
            System.out.println("" + e.getKey() + " " + e.getValue());
        }
    }

    /**
     * Return the tablets in the range startRow -> endRow
     *
     * Metadata rowIDs = TableID:EndRow - with EndRow = Inclusive and PrevEndRow(cf = ~tab,cq = ~pr) = Exclusive
     * To find Tablets in the Range StartRange -> EndRange
     *    If (StartRange <= EndRow) AND (EndRange > PrevEndRow) THEN Tablet is in Range!
     *
     */
    private static Map<KeyExtent, String> findTabletsAndLocations(Table.ID tableId, Text startRowRange, Text endRowRange, Scanner metaDataScanner) {
        Map<KeyExtent, String> tabletToLocation = new TreeMap<>();
        Text mStartRange = new Text(tableId.getUtf8());
        mStartRange.append(new byte[] {';'}, 0, 1);
        mStartRange.append(startRowRange.getBytes(), 0, startRowRange.getLength());
        Text mEndrange = new Text(tableId.getUtf8());
        mEndrange.append(new byte[] {';'}, 0, 1);
        mEndrange.append(endRowRange.getBytes(), 0, endRowRange.getLength());

        metaDataScanner.setRange(new KeyExtent(tableId, null, null).toMetadataRange());

        System.out.println("Scanning entries in Metadata for Range " + startRowRange + "-" + endRowRange);
        // for simplicity make prevEndRow something small instead of null
        Text prevEndRow = new Text("-1");
        for (Map.Entry<Key, Value> e : metaDataScanner) {
            if (e.getKey().compareColumnFamily(new Text("loc")) != 0)
                continue;
            Text endRowCurrent = e.getKey().getRow();
            //System.out.println("comparing keys " + row + " " + metadataLikeStartRow);

            // I believe this is something like TabletLocatorImpl.lookupTabletLocation & MetadataLocationObtainer
            if (mStartRange.compareTo(endRowCurrent) <= 0 && mEndrange.compareTo(prevEndRow) > 0) {
                //System.out.println("range is in endRow = " + endRowCurrent + " with prevEndRow = " + prevRow);
                tabletToLocation.put(new KeyExtent(tableId, endRowCurrent, prevEndRow), e.getValue().toString());
            }
            prevEndRow = endRowCurrent;
        }
        return tabletToLocation;
    }

    /**
     * Return the tablets in the range startRow -> endRow
     *
     * Metadata rowIDs = TableID:EndRow - with EndRow = Inclusive and PrevEndRow(cf = ~tab,cq = ~pr) = Exclusive
     * To find Tablets in the Range StartRange -> EndRange
     *    If (StartRange < EndRow) AND (EndRange > PrevEndRow) THEN Tablet is in Range!
     *
     */
    private static List<Tablet> findOverlappingTablets(Table.ID tableId, Text startRowRange, Text endRowRange, Scanner metaDataScanner) {
        List<Tablet> tablets = new ArrayList<>();
        Text mStartRange = new Text(tableId.getUtf8());
        mStartRange.append(new byte[] {';'}, 0, 1);
        mStartRange.append(startRowRange.getBytes(), 0, startRowRange.getLength());
        Text mEndrange = new Text(tableId.getUtf8());
        mEndrange.append(new byte[] {';'}, 0, 1);
        mEndrange.append(endRowRange.getBytes(), 0, endRowRange.getLength());

        metaDataScanner.setRange(new KeyExtent(tableId, null, null).toMetadataRange());

        System.out.println("Scanning entries in Metadata for Range " + startRowRange + "-" + endRowRange);
        // for simplicity make prevEndRow something small instead of null
        Text prevEndRow = null;
        for (Map.Entry<Key, Value> e : metaDataScanner) {
            if (e.getKey().compareColumnFamily(new Text("~tab")) != 0)
                continue;
            Text endRowCurrent = e.getKey().getRow();
            System.out.println("comparing keys " + mStartRange + " " + endRowCurrent);

            // I believe this is something like TabletLocatorImpl.lookupTabletLocation & MetadataLocationObtainer
            if (mStartRange.compareTo(endRowCurrent) <= 0 && (prevEndRow == null || mEndrange.compareTo(prevEndRow) > 0)) {
                System.out.println("range is in endRow = " + endRowCurrent + " with prevEndRow = " + prevEndRow);
                tablets.add(new Tablet(tableId, endRowCurrent, prevEndRow));
            }
            prevEndRow = endRowCurrent;
        }
        return tablets;
    }

    /**
     * Create Range to Rfiles Mapping for rows: row000000 -> row000999
     * The range should match up with splits so one range will be 1 to many Tablets
     *
     * @param numOfFiles - number of rfiles containing the rows
     * @return Mapping of Range -> Files
     */
    private static SortedMap<Range, List<File>> createRangeFileMapping(int numOfFiles) throws Exception{
        //FileSystem hdfs = FileSystem.get(new URI("hdfs://localhost:8020"), new Configuration());
        SortedMap<Range, List<File>> mapping = new TreeMap<>();
        List<RFileWriter> writers = new ArrayList<>();
        List<File> rFiles = new ArrayList<>();

        FileSystem fs = FileSystem.getLocal(new Configuration());

        if(bulkDir.exists()) {
            for (File rFile : bulkDir.listFiles()) {
                rFile.delete();
            }
            bulkDir.delete();
        }

        // create the rfiles
        bulkDir.mkdirs();
        for (int i = 0; i < numOfFiles; i++) {
            File rFile = new File(bulkDir, "test" + i + ".rf");
            RFileWriter writer = RFile.newWriter().to(rFile.getPath()).withFileSystem(fs).build();
            writers.add(writer);
            rFiles.add(rFile);
        }
        //write the rows to the rfiles
        Set<Map.Entry<Key, Value>> data = createTestData(1000, 1, 1).entrySet();
        int rowsPerFile = 1000 / numOfFiles;
        int count = 0;
        int fileIndex = 0;
        //System.out.println("Looping through " + data.size() + " rows and putting " + rowsPerFile + " per file.");
        for (Map.Entry<Key, Value> e : data) {
            if (count < rowsPerFile) {
                writers.get(fileIndex).append(e.getKey(), e.getValue());
                count++;
            } else {
                //System.out.println("Close writer for  " + fileIndex);
                writers.get(fileIndex).close();
                fileIndex++;
                count = 0;
            }
        }
        //System.out.println("Close writer for  " + fileIndex);
        writers.get(fileIndex).close();


        // verify the start and end rows
        Map.Entry<Key, Value> outerStart = null;
        Map.Entry<Key, Value> outerEnd = null;
        for(File r : rFiles) {
            Scanner rscanner = RFile.newScanner().from(r.getPath()).withFileSystem(fs).build();
            //System.out.println("Scanning rfile... " + r.getName());
            Map.Entry<Key, Value> start = null;
            Map.Entry<Key, Value> end = null;
            for (Map.Entry<Key, Value> e : rscanner) {
                if (start == null)
                    start = e;
                end = e;
            }
            System.out.println("First Row: " + start.getKey() + " " + start.getValue());
            System.out.println(" Last Row: " + end.getKey() + " " + end.getValue());
            if(outerStart == null)
                outerStart = start;
            outerEnd = end;
        }
        mapping.put(new Range(outerStart.getKey(), outerEnd.getKey()), rFiles);
        return mapping;
    }

    private static SortedMap<Key,Value> createTestData(int rows, int families, int qualifiers) {
        TreeMap<Key,Value> testData = new TreeMap<>();
        int startRow = 0;
        int startFamily = 0;

        for (int r = 0; r < rows; r++) {
            String row = rowStr(r + startRow);
            for (int f = 0; f < families; f++) {
                String fam = colStr(f + startFamily);
                for (int q = 0; q < qualifiers; q++) {
                    String qual = colStr(q);
                    Key k = new Key(row, fam, qual);
                    testData.put(k, new Value("val" + r));
                }
            }
        }

        return testData;
    }

    static String rowStr(int r) {
        return String.format("row%06d", r);
    }

    static String colStr(int c) {
        return String.format("c%04d", c);
    }
    static String rowStrHex(int r) {
        return String.format("%06x", r);
    }

    static String colStrHex(int c) {
        return String.format("%04x", c);
    }
}
