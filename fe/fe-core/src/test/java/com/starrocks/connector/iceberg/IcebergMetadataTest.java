// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.iceberg;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.Table;
import com.starrocks.common.AlreadyExistsException;
import com.starrocks.common.DdlException;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.connector.iceberg.cost.IcebergMetricsReporter;
import com.starrocks.thrift.TIcebergColumnStats;
import com.starrocks.thrift.TIcebergDataFile;
import com.starrocks.thrift.TSinkCommitInfo;
import mockit.Expectations;
import mockit.Mocked;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hive.HiveTableOperations;
import org.apache.iceberg.hive.IcebergHiveCatalog;
import org.apache.thrift.TException;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.starrocks.catalog.Table.TableType.ICEBERG;

public class IcebergMetadataTest extends TableTestBase {
    private static final String CATALOG_NAME = "IcebergCatalog";

    @Test
    public void testListDatabaseNames(@Mocked IcebergCatalog icebergCatalog) {
        new Expectations() {
            {
                icebergCatalog.listAllDatabases();
                result = Lists.newArrayList("db1", "db2");
                minTimes = 0;
            }
        };

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergCatalog);
        List<String> expectResult = Lists.newArrayList("db1", "db2");
        Assert.assertEquals(expectResult, metadata.listDbNames());
    }

    @Test
    public void testGetDB(@Mocked IcebergHiveCatalog icebergHiveCatalog) throws Exception {
        String db = "db";

        new Expectations() {
            {
                icebergHiveCatalog.getDB(db);
                result = new Database(0, db);
                minTimes = 0;
            }
        };

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        Database expectResult = new Database(0, db);
        Assert.assertEquals(expectResult, metadata.getDb(db));
    }


    @Test
    public void testListTableNames(@Mocked IcebergHiveCatalog icebergHiveCatalog) {
        String db1 = "db1";
        String tbl1 = "tbl1";
        String tbl2 = "tbl2";

        new Expectations() {
            {
                icebergHiveCatalog.listTables(Namespace.of(db1));
                result = Lists.newArrayList(TableIdentifier.of(db1, tbl1), TableIdentifier.of(db1, tbl2));
                minTimes = 0;
            }
        };

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        List<String> expectResult = Lists.newArrayList("tbl1", "tbl2");
        Assert.assertEquals(expectResult, metadata.listTableNames(db1));
    }

    @Test
    public void testGetTable(@Mocked IcebergHiveCatalog icebergHiveCatalog,
                             @Mocked HiveTableOperations hiveTableOperations) {

        new Expectations() {
            {
                icebergHiveCatalog.loadTable(TableIdentifier.of("db", "tbl"));
                result = new BaseTable(hiveTableOperations, "tbl");
                minTimes = 0;
            }
        };

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        Table actual = metadata.getTable("db", "tbl");
        Assert.assertEquals("tbl", actual.getName());
        Assert.assertEquals(ICEBERG, actual.getType());
    }

    @Test
    public void testNotExistTable(@Mocked IcebergHiveCatalog icebergHiveCatalog,
                                  @Mocked HiveTableOperations hiveTableOperations) {
        new Expectations() {
            {
                icebergHiveCatalog.loadTable(TableIdentifier.of("db", "tbl"), (Optional<IcebergMetricsReporter>) any);
                result = new BaseTable(hiveTableOperations, "tbl");
                minTimes = 0;

                icebergHiveCatalog.loadTable(TableIdentifier.of("db", "tbl2"), (Optional<IcebergMetricsReporter>) any);
                result = new StarRocksConnectorException("not found");
            }
        };

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        Assert.assertNull(metadata.getTable("db", "tbl2"));
    }

    @Test(expected = AlreadyExistsException.class)
    public void testCreateDuplicatedDb(@Mocked IcebergHiveCatalog icebergHiveCatalog) throws AlreadyExistsException {
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        new Expectations() {
            {
                icebergHiveCatalog.listAllDatabases();
                result = Lists.newArrayList("iceberg_db");
                minTimes = 0;
            }
        };

        metadata.createDb("iceberg_db", new HashMap<>());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateDbWithErrorConfig() throws AlreadyExistsException {
        IcebergHiveCatalog hiveCatalog = new IcebergHiveCatalog();
        hiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, hiveCatalog);

        new Expectations(hiveCatalog) {
            {
                hiveCatalog.listAllDatabases();
                result = Lists.newArrayList();
                minTimes = 0;
            }
        };

        metadata.createDb("iceberg_db", ImmutableMap.of("error_key", "error_value"));
    }

    @Test
    public void testCreateDbInvalidateLocation() {
        IcebergHiveCatalog hiveCatalog = new IcebergHiveCatalog();
        hiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, hiveCatalog);

        new Expectations(hiveCatalog) {
            {
                hiveCatalog.listAllDatabases();
                result = Lists.newArrayList();
                minTimes = 0;
            }
        };

        try {
            metadata.createDb("iceberg_db", ImmutableMap.of("location", "hdfs:xx/aaaxx"));
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e instanceof StarRocksConnectorException);
            Assert.assertTrue(e.getMessage().contains("Invalid location URI"));
        }
    }

    @Test
    public void testNormalCreateDb() throws AlreadyExistsException, DdlException {
        IcebergHiveCatalog hiveCatalog = new IcebergHiveCatalog();
        hiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, hiveCatalog);

        new Expectations(hiveCatalog) {
            {
                hiveCatalog.listAllDatabases();
                result = Lists.newArrayList();
                minTimes = 0;

                hiveCatalog.createHiveDatabase((org.apache.hadoop.hive.metastore.api.Database) any);
                result = null;
                minTimes = 0;
            }
        };
        metadata.createDb("iceberg_db");
    }

    @Test
    public void testDropNotEmptyTable() {
        IcebergHiveCatalog icebergHiveCatalog = new IcebergHiveCatalog();
        icebergHiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        List<TableIdentifier> mockTables = new ArrayList<>();
        mockTables.add(TableIdentifier.of("table1"));
        mockTables.add(TableIdentifier.of("table2"));

        new Expectations(icebergHiveCatalog) {
            {
                icebergHiveCatalog.listTables(Namespace.of("iceberg_db"));
                result = mockTables;
                minTimes = 0;
            }
        };

        try {
            metadata.dropDb("iceberg_db", true);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e instanceof StarRocksConnectorException);
            Assert.assertTrue(e.getMessage().contains("Database iceberg_db not empty"));
        }
    }

    @Test
    public void testDropDbFailed() throws TException, InterruptedException {
        IcebergHiveCatalog icebergHiveCatalog = new IcebergHiveCatalog();
        icebergHiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);

        new Expectations(icebergHiveCatalog) {
            {
                icebergHiveCatalog.listTables(Namespace.of("iceberg_db"));
                result = Lists.newArrayList();
                minTimes = 0;
            }
        };

        try {
            metadata.dropDb("iceberg_db", true);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e instanceof MetaNotFoundException);
            Assert.assertTrue(e.getMessage().contains("Failed to access database"));
        }

        new Expectations(icebergHiveCatalog) {
            {
                icebergHiveCatalog.getDB("iceberg_db");
                result = null;
                minTimes = 0;
            }
        };

        try {
            metadata.dropDb("iceberg_db", true);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e instanceof MetaNotFoundException);
            Assert.assertTrue(e.getMessage().contains("Not found database"));
        }

        new Expectations(icebergHiveCatalog) {
            {
                icebergHiveCatalog.getDB("iceberg_db");
                result = new Database();
                minTimes = 0;
            }
        };

        try {
            metadata.dropDb("iceberg_db", true);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e instanceof MetaNotFoundException);
            Assert.assertTrue(e.getMessage().contains("Database location is empty"));
        }
    }

    @Test
    public void testNormalDropDb() throws MetaNotFoundException, TException, InterruptedException {
        IcebergHiveCatalog icebergHiveCatalog = new IcebergHiveCatalog();
        icebergHiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);

        new Expectations(icebergHiveCatalog) {
            {
                icebergHiveCatalog.listTables(Namespace.of("iceberg_db"));
                result = Lists.newArrayList();
                minTimes = 0;

                icebergHiveCatalog.getDB("iceberg_db");
                result = new Database(1, "db", "hdfs:namenode:9000/user/hive/iceberg_location");
                minTimes = 0;

                icebergHiveCatalog.dropDatabaseInHiveMetastore("iceberg_db");
                result = null;
                minTimes = 0;
            }
        };
        metadata.dropDb("iceberg_db", true);
    }

    @Test
    public void testFinishSink() {
        IcebergHiveCatalog icebergHiveCatalog = new IcebergHiveCatalog();
        icebergHiveCatalog.initialize("iceberg_catalog", new HashMap<>());

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        IcebergTable icebergTable = new IcebergTable(1, "srTableName", "iceberg_catalog", "resource_name", "iceberg_db",
                "iceberg_table", Lists.newArrayList(), table, Maps.newHashMap());

        new Expectations(metadata) {
            {
                metadata.getTable(anyString, anyString);
                result = icebergTable;
                minTimes = 0;
            }
        };

        TSinkCommitInfo tSinkCommitInfo = new TSinkCommitInfo();
        TIcebergDataFile tIcebergDataFile = new TIcebergDataFile();
        String path = table.location() + "/data/data_bucket=0/c.parquet";
        String format = "parquet";
        long recordCount = 10;
        long fileSize = 2000;
        String partitionPath = table.location() + "/data/data_bucket=0/";
        List<Long> splitOffsets = Lists.newArrayList(4L);
        tIcebergDataFile.setPath(path);
        tIcebergDataFile.setFormat(format);
        tIcebergDataFile.setRecord_count(recordCount);
        tIcebergDataFile.setSplit_offsets(splitOffsets);
        tIcebergDataFile.setPartition_path(partitionPath);
        tIcebergDataFile.setFile_size_in_bytes(fileSize);

        tSinkCommitInfo.setIs_overwrite(false);
        tSinkCommitInfo.setIceberg_data_file(tIcebergDataFile);

        metadata.finishSink("iceberg_db", "iceberg_table", Lists.newArrayList(tSinkCommitInfo));

        List<FileScanTask> fileScanTasks = Lists.newArrayList(table.newScan().planFiles());
        Assert.assertEquals(1, fileScanTasks.size());
        FileScanTask task = fileScanTasks.get(0);
        Assert.assertEquals(0, task.deletes().size());
        DataFile dataFile = task.file();
        Assert.assertEquals(path, dataFile.path());
        Assert.assertEquals(format, dataFile.format().name().toLowerCase(Locale.ROOT));
        Assert.assertEquals(1, dataFile.partition().size());
        Assert.assertEquals(recordCount, dataFile.recordCount());
        Assert.assertEquals(fileSize, dataFile.fileSizeInBytes());
        Assert.assertEquals(4, dataFile.splitOffsets().get(0).longValue());

        tSinkCommitInfo.setIs_overwrite(true);
        recordCount = 22;
        fileSize = 3333;
        tIcebergDataFile.setRecord_count(recordCount);
        tIcebergDataFile.setFile_size_in_bytes(fileSize);
        Map<Integer, Long> valueCounts = new HashMap<>();
        valueCounts.put(1, 111L);
        TIcebergColumnStats columnStats = new TIcebergColumnStats();
        columnStats.setColumn_sizes(new HashMap<>());
        columnStats.setValue_counts(valueCounts);
        columnStats.setNull_value_counts(new HashMap<>());
        columnStats.setLower_bounds(new HashMap<>());
        columnStats.setUpper_bounds(new HashMap<>());
        tIcebergDataFile.setColumn_stats(columnStats);

        tSinkCommitInfo.setIceberg_data_file(tIcebergDataFile);

        metadata.finishSink("iceberg_db", "iceberg_table", Lists.newArrayList(tSinkCommitInfo));
        table.refresh();
        TableScan scan = table.newScan().includeColumnStats();
        fileScanTasks = Lists.newArrayList(scan.planFiles());

        Assert.assertEquals(1, fileScanTasks.size());
        task = fileScanTasks.get(0);
        Assert.assertEquals(0, task.deletes().size());
        dataFile = task.file();
        Assert.assertEquals(path, dataFile.path());
        Assert.assertEquals(format, dataFile.format().name().toLowerCase(Locale.ROOT));
        Assert.assertEquals(1, dataFile.partition().size());
        Assert.assertEquals(recordCount, dataFile.recordCount());
        Assert.assertEquals(fileSize, dataFile.fileSizeInBytes());
        Assert.assertEquals(4, dataFile.splitOffsets().get(0).longValue());
        Assert.assertEquals(111L, dataFile.valueCounts().get(1).longValue());
    }
}