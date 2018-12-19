package org.cam.storage.levelgraph.storage;

import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.kernel.impl.store.UnderlyingStorageException;
import org.neo4j.kernel.impl.store.record.RecordLoad;
import org.neo4j.kernel.impl.store.record.RelationshipRecord;

import java.io.*;
import java.io.FileWriter;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Handles a store with the following format:
 * File edges, File metadata
 * Edges: 48 bits/6 bytes.
 * No edgeid separately provided as the edgeid maps directly to the storage.
 */

public class NeRelationshipStore {

    public static final int dataPageSize = 4096, metaDataPageSize = 4096, nodeHeader = 25;
    public static final int recordSize = 6, recordsPerPage = 4096 / 6;
    public static final int metadataBlock = 57; //8+1+8+8+32
    PagedFile dataStore, metadataStore, nodePrev;
    ArrayList<Long> offsets;
    HashMap<Long, OffsetData> nodeOffset;
    long currentOffset;
    BufferedWriter offsetWriter;
    RocksDBInterface rocksDBInterface;
    public NeRelationshipStore(String filename, PageCache pageCache) {
        nodeOffset = new HashMap<>();
        currentOffset = 0;
        try {
            dataStore = pageCache.map(new File(filename), dataPageSize, StandardOpenOption.CREATE);
            metadataStore = pageCache.map(new File(filename + ".meta"), metaDataPageSize, StandardOpenOption.CREATE);
            nodePrev = pageCache.map(new File(filename + ".nodep"), metaDataPageSize, StandardOpenOption.CREATE);
            File loffsetFile = new File(filename+".offset");

            if(!loffsetFile.exists()) {
                loffsetFile.createNewFile(); //creating it
            }

            offsetWriter = new BufferedWriter(new FileWriter(filename + ".offset", true));
            BufferedReader reader = new BufferedReader(new FileReader(filename + ".offset"));
            int value;
            value = reader.read();
            long tempValue = 0;
            int counter = 0;
            while (value != -1) {
                tempValue <<= 8;
                tempValue += value;
                counter++;
                if (counter == 8) {
                    offsets.add(tempValue);
                    counter = 0;
                }
                value = reader.read();
            }
            if (offsets.size() > 0)
                currentOffset = offsets.get(offsets.size() - 1);
        } catch (IOException e) {
            throw new UnderlyingStorageException(e);
        }
    }

    /**
     * Record format: 48 bits, 0-3 second offset, 4-7: flags;
     * 8-23: First node id. 24-39: Second node relative id.
     * 40-45: first node offset, 46-48 second offset.
     *
     * @param id
     * @param record
     * @param mode
     * @return
     */

    public RelationshipRecord getRecord(long id, RelationshipRecord record, RecordLoad mode) {
        int firstNode, firstOffset, secondNode, secondOffset;
        record.setId(id);
        try (PageCursor cursor = dataStore.io(0, PagedFile.PF_SHARED_READ_LOCK)) {
            long pageId = pageIdForRecord(id);
            int offset = offetForRecord(id);
            if (cursor.next(pageId)) {
                do {
                    prepareForReading(cursor, offset, record);
                    byte header = cursor.getByte();
                    boolean inUse = (header & 0x1) != 0;
                    record.setInUse(inUse);
                    if (mode.shouldLoad(inUse)) {
                        record.setFirstInFirstChain((header & 0x2) != 0);
                        record.setFirstInSecondChain((header & 0x4) != 0);
                        byte[] data = new byte[5];
                        cursor.getBytes(data, 0, 5);
                        firstNode = (int) (data[0]) + ((int) data[1]) << 8;
                        secondNode = (int) (data[2]) + ((int) data[3]) << 8;
                        firstOffset = data[4] & (0x3f);
                        secondOffset = (data[4] & (0xc0)) >> 2 + (header & (0xf0)) >> 4;
                        fillNodeData(id, firstNode, secondNode, firstOffset, secondOffset, record);
                    }
                } while (cursor.shouldRetry());
            }
        } catch (IOException e) {
            throw new UnderlyingStorageException(e);
        }
        return record;
    }

    void getRelationData(PageCursor cursor, long id, int node, int offset, boolean first, RelationshipRecord record) {
        long pageId = idToOffset(id) / dataPageSize;
        try {
            if (cursor.next(pageId)) {
                do {
                    long node1 = cursor.getLong(node);
                    if (first) {
                        record.setFirstNode(node1);
                    } else {
                        record.setSecondNode(node1);
                    }
                    int count = cursor.getByte(node + 8);
                    if (offset != 0 && (offset / 8) < (count - 1)) {
                        long prevRelation = cursor.getLong(offset + node + nodeHeader);
                        long nextRelation = cursor.getLong(node + nodeHeader + offset - 1);
                        if (first) {
                            record.setFirstPrevRel(prevRelation);
                            record.setFirstNextRel(nextRelation);
                        } else {
                            record.setSecondPrevRel(prevRelation);
                            record.setSecondNextRel(nextRelation);
                        }
                    } else {
                        if (offset == 0) {
                            {
                                if ((first && record.isFirstInFirstChain()) || (!first && record.isFirstInSecondChain())) {
                                    if (first) {
                                        record.setFirstPrevRel(-1);
                                    } else {
                                        record.setSecondPrevRel(-1);
                                    }
                                } else {
                                    long page = cursor.getLong(node + 9);
                                    long recordPageId = pageIdForRecord(page);
                                    int recordOffset = offetForRecord(page);
                                    try (PageCursor cursor2 = dataStore.io(0, PagedFile.PF_SHARED_READ_LOCK)) {
                                        do {
                                            cursor2.next(recordPageId);
                                            int count2 = cursor2.getInt(recordOffset);
                                            long prev = cursor2.getLong((recordOffset) + count2 * 8 + nodeHeader);
                                            if (first) {
                                                record.setFirstPrevRel(prev);
                                            } else {
                                                record.setSecondPrevRel(prev);
                                            }
                                        } while (cursor2.shouldRetry());
                                    }
                                }
                            }
                        }
                        if ((offset / 8) == (count - 1)) {
                            long page = cursor.getLong(node + 17);
                            long recordPageId = pageIdForRecord(page);
                            int recordOffset = offetForRecord(page);
                            try (PageCursor cursor2 = dataStore.io(0, PagedFile.PF_SHARED_READ_LOCK)) {
                                do {
                                    cursor2.next(recordPageId);
                                    int count2 = cursor2.getInt(recordOffset);
                                    long prev = cursor2.getLong((recordOffset) + count2 * 8 + nodeHeader);
                                    record.setFirstPrevRel(prev);
                                    if (first) {
                                        record.setFirstNextRel(prev);
                                    } else {
                                        record.setSecondNextRel(prev);
                                    }
                                } while (cursor2.shouldRetry());
                            }

                        }
                    }

                } while (cursor.shouldRetry());
            }
        } catch (IOException e) {
            throw new UnderlyingStorageException(e);
        }
    }

    void fillNodeData(long id, int firstNode, int secondNode, int firstOffset, int secondOffset, RelationshipRecord record) {

        try (PageCursor cursor = metadataStore.io(0, PagedFile.PF_SHARED_READ_LOCK)) {
            getRelationData(cursor, id, firstNode, firstOffset, true, record);
            getRelationData(cursor, id, secondNode, secondOffset, false, record);
        } catch (IOException e) {
            throw new UnderlyingStorageException(e);
        }
    }

    long idToOffset(long id) {
        return offsets.get((int) (id >> 16));
    }

    private void prepareForReading(PageCursor cursor, int offset, RelationshipRecord record) {
        // Mark this record as unused. This to simplify implementations of readRecord.
        // readRecord can behave differently depending on RecordLoad argument and so it may be that
        // contents of a record may be loaded even if that record is unused, where the contents
        // can still be initialized data. Know that for many record stores, deleting a record means
        // just setting one byte or bit in that record.
        record.setInUse(false);
        cursor.setOffset(offset);
    }

    long pageIdForRecord(long id) {
        return id / recordsPerPage;
    }

    int offetForRecord(long id) {
        return (int) ((id % recordsPerPage) * recordSize);
    }

    long pageIdForNodeRecord(long offset) {
        return offset / metaDataPageSize;
    }

    int offsetForNodeRecord(long offset) {
        return (int) (offset % metaDataPageSize);
    }

    public void readIntoRecord(long id, RelationshipRecord record, RecordLoad mode, PageCursor cursor) throws IOException {
        int firstNode, firstOffset, secondNode, secondOffset;
            long pageId = pageIdForRecord(id);
            int offset = offetForRecord(id);
            if (cursor.next(pageId)) {
                do {
                    prepareForReading(cursor, offset, record);
                    byte header = cursor.getByte();
                    boolean inUse = (header & 0x1) != 0;
                    record.setInUse(inUse);
                    if (mode.shouldLoad(inUse)) {
                        record.setFirstInFirstChain((header & 0x2) != 0);
                        record.setFirstInSecondChain((header & 0x4) != 0);
                        byte[] data = new byte[5];
                        cursor.getBytes(data, 0, 5);
                        firstNode = (int) (data[0]) + ((int) data[1]) << 8;
                        secondNode = (int) (data[2]) + ((int) data[3]) << 8;
                        firstOffset = data[4] & (0x3f);
                        secondOffset = (data[4] & (0xc0)) >> 2 + (header & (0xf0)) >> 4;
                        fillNodeData(id, firstNode, secondNode, firstOffset, secondOffset, record);
                    }
                } while (cursor.shouldRetry());
            }
    }

    void updateNodeBlock(long nodeId, long relationId) {
        if (!nodeOffset.containsKey(nodeId)) {
            long prev;
            try(PageCursor cursor= nodePrev.io(0, PagedFile.PF_SHARED_READ_LOCK)){
                cursor.next(nodeId/metaDataPageSize);
                prev=cursor.getLong((int)(nodeId%metaDataPageSize));
                initialiseNodeBlock(currentOffset, nodeId, prev);
            }catch (IOException e){
                throw new UnderlyingStorageException(e);
            }

        }
        try (PageCursor cursor = metadataStore.io(0, PagedFile.PF_SHARED_READ_LOCK)) {
            if (nodeOffset.get(nodeId).count == 4) {
                long dataPoint = currentOffset;
                initialiseNodeBlock(dataPoint, nodeId, nodeOffset.get(nodeId).begin);
                nodeOffset.get(nodeId).begin = dataPoint;
                nodeOffset.get(nodeId).count = 0;
                currentOffset += metadataBlock;
            }
            OffsetData data = nodeOffset.get(nodeId);
            long absoluteOffset = (data.begin + 9 + data.count * 8);
            cursor.next(pageIdForNodeRecord(absoluteOffset));
            int pointOffset = offsetForNodeRecord(absoluteOffset);
            cursor.putLong(pointOffset, relationId);
        } catch (IOException e) {
            throw new UnderlyingStorageException(e);
        }
    }

    public void updateRecord(RelationshipRecord record) {
        if (record.isCreated()) {
            if ((record.getId() % 65536) == 0) {
                metadataFlush();
            }
            updateNodeBlock(record.getFirstNode(), record.getId());
            updateNodeBlock(record.getSecondNode(), record.getId());

        }
    }

    void initialiseNodeBlock(long offset, long nodeId, long prev) {
        try(PageCursor cursor= nodePrev.io(0, PagedFile.PF_SHARED_READ_LOCK)){
                cursor.next(nodeId/metaDataPageSize);
                cursor.putLong((int) nodeId%metaDataPageSize, prev);
            }catch (IOException e){
                throw new UnderlyingStorageException(e);
            }
        try (PageCursor cursor = metadataStore.io(0, PagedFile.PF_SHARED_READ_LOCK)) {
            cursor.next(pageIdForNodeRecord(prev));
            cursor.putLong(offsetForNodeRecord(prev) + 17, offset);
            cursor.next(pageIdForNodeRecord(offset));
            cursor.putLong(offsetForNodeRecord(offset), nodeId);

            cursor.putByte(offsetForNodeRecord(offset) + 8, (byte) 0);
            cursor.putLong(offsetForNodeRecord(offset) + 9, prev);
            cursor.putLong(offsetForNodeRecord(offset) + 17, -1);

        } catch (IOException e) {
            throw new UnderlyingStorageException(e);
        }
    }

    void metadataFlush() {
        nodeOffset.clear();
        try {
            for (int i = 0; i < 8; i++) {
                int data = (int) ((currentOffset >> (i * 8)) & 0xFL);
                offsetWriter.write(data);
            }
        } catch (IOException e) {
            throw new UnderlyingStorageException(e);
        }
    }

    class OffsetData {
        long begin, end;
        int count;

        OffsetData() {
            begin = -1;
            end = -1;
            count = 0;
        }
    }
}
