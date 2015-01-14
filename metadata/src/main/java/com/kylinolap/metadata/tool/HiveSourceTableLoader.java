package com.kylinolap.metadata.tool;

/*
 * Copyright 2013-2014 eBay Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.kylinolap.common.KylinConfig;
import com.kylinolap.common.util.HadoopUtil;
import com.kylinolap.metadata.MetadataConstances;
import com.kylinolap.metadata.MetadataManager;
import com.kylinolap.metadata.model.ColumnDesc;
import com.kylinolap.metadata.model.TableDesc;

/**
 * Management class to sync hive table metadata with command See main method for
 * how to use the class
 * 
 * @author jianliu
 */
public class HiveSourceTableLoader {

    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(HiveSourceTableLoader.class);

    public static final String OUTPUT_SURFIX = "json";
    public static final String TABLE_FOLDER_NAME = "table";
    public static final String TABLE_EXD_FOLDER_NAME = "table_exd";

    public static Set<String> reloadHiveTables(String[] hiveTables, KylinConfig config) throws IOException {
        
        Map<String, Set<String>> db2tables = Maps.newHashMap();
        for (String table : hiveTables) {
            String[] dbtableNames = HadoopUtil.parseHiveTableName(table);
            Set<String> set = db2tables.get(dbtableNames[0]);
            if (set == null) {
                set = Sets.newHashSet();
                db2tables.put(dbtableNames[0], set);
            }
            set.add(dbtableNames[1]);
        }

        for (String database : db2tables.keySet()) {
            for (String table : db2tables.get(database)) {
                TableDesc tableDesc = MetadataManager.getInstance(config).getTableDesc(table);
                if (tableDesc == null) {
                    continue;
                }
                if (tableDesc.getDatabase().equalsIgnoreCase(database)) {
                    continue;
                } else {
                    throw new UnsupportedOperationException(String.format("there is already a table[%s] in database[%s]", tableDesc.getName(), tableDesc.getDatabase()));
                }
            }
        }

        // extract from hive
        Set<String> loadedTables = Sets.newHashSet();
        for (String database : db2tables.keySet()) {
            List<String> loaded = extractHiveTables(database, db2tables.get(database), config);
            loadedTables.addAll(loaded);
        }

        return loadedTables;
    }

    private static List<String> extractHiveTables(String database, Set<String> tables, KylinConfig config) throws IOException {

        List<String> loadedTables = Lists.newArrayList();
        MetadataManager metaMgr = MetadataManager.getInstance(KylinConfig.getInstanceFromEnv());
        for (String tableName : tables) {
            Table table = null;
            HiveClient hiveClient = new HiveClient();
            List<FieldSchema> fields = null;
            try {
                table = hiveClient.getHiveTable(database, tableName);
                fields = hiveClient.getHiveTableFields(database, tableName);
            } catch (Exception e) {
                e.printStackTrace();
                throw new IOException(e);
            } 

            long tableSize = hiveClient.getFileSizeForTable(table);
            long tableFileNum = hiveClient.getFileNumberForTable(table);
            TableDesc tableDesc = metaMgr.getTableDesc(database + "." + tableName);
            if (tableDesc == null) {
                tableDesc = new TableDesc();
                tableDesc.setDatabase(database.toUpperCase());
                tableDesc.setName(tableName.toUpperCase());
                tableDesc.setUuid(UUID.randomUUID().toString());
                tableDesc.setLastModified(0);
            }

            int columnNumber = fields.size();
            List<ColumnDesc> columns = new ArrayList<ColumnDesc>(columnNumber);
            for (int i = 0; i < columnNumber; i++) {
                FieldSchema field = fields.get(i);
                ColumnDesc cdesc = new ColumnDesc();
                cdesc.setName(field.getName().toUpperCase());
                cdesc.setDatatype(field.getType());
                cdesc.setId(String.valueOf(i + 1));
                columns.add(cdesc);
            }
            tableDesc.setColumns(columns.toArray(new ColumnDesc[columnNumber]));
            
            List<FieldSchema> partitionCols = table.getPartitionKeys();
            StringBuffer partitionColumnString = new StringBuffer();
            for(int i=0, n= partitionCols.size(); i<n; i++) {
                if(i >0)
                    partitionColumnString.append(", ");
                partitionColumnString.append(partitionCols.get(i).getName().toUpperCase());
            }
            
            Map<String, String> map = metaMgr.getTableDescExd(tableDesc.getIdentity());
            
            if(map == null) {
                map =  Maps.newHashMap();
            }
            map.put(MetadataConstances.TABLE_EXD_TABLENAME, table.getTableName());
            map.put(MetadataConstances.TABLE_EXD_LOCATION, table.getSd().getLocation());
            map.put(MetadataConstances.TABLE_EXD_IF, table.getSd().getInputFormat());
            map.put(MetadataConstances.TABLE_EXD_OF, table.getSd().getOutputFormat());
            map.put(MetadataConstances.TABLE_EXD_OWNER, table.getOwner());
            map.put(MetadataConstances.TABLE_EXD_LAT, String.valueOf(table.getLastAccessTime()));
            map.put(MetadataConstances.TABLE_EXD_PC, partitionColumnString.toString());
            map.put(MetadataConstances.TABLE_EXD_TFS, String.valueOf(tableSize));
            map.put(MetadataConstances.TABLE_EXD_TNF, String.valueOf(tableFileNum));
            map.put(MetadataConstances.TABLE_EXD_PARTITIONED, Boolean.valueOf(partitionCols != null && partitionCols.size()>0).toString());

            metaMgr.saveSourceTable(tableDesc);
            metaMgr.saveTableExd(tableDesc.getIdentity(), map);
            loadedTables.add(tableDesc.getIdentity());
        }


        return loadedTables;
    }


}
