package org.datavec.arrow;

import lombok.val;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.datavec.api.records.Record;
import org.datavec.api.records.metadata.RecordMetaData;
import org.datavec.api.records.metadata.RecordMetaDataIndex;
import org.datavec.api.records.reader.RecordReader;
import org.datavec.api.split.FileSplit;
import org.datavec.api.transform.schema.Schema;
import org.datavec.api.writable.DoubleWritable;
import org.datavec.api.writable.Writable;
import org.datavec.arrow.recordreader.ArrowRecordReader;
import org.datavec.arrow.recordreader.ArrowRecordWriter;
import org.datavec.arrow.recordreader.ArrowWritableRecordBatch;
import org.junit.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.primitives.Pair;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

import static java.nio.channels.Channels.newChannel;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ArrowConverterTest {

    @Test
    public void testRecordWriter() throws Exception {
        Pair<Schema, List<List<Writable>>> schemaListPair = recordToWrite();
        ArrowRecordWriter arrowRecordWriter = new ArrowRecordWriter(schemaListPair.getFirst());
        arrowRecordWriter.writeBatch(schemaListPair.getRight());
    }

    @Test
    public void testCreateNDArray() throws Exception {
        val recordsToWrite = recordToWrite();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ArrowConverter.writeRecordBatchTo(recordsToWrite.getRight(),recordsToWrite.getFirst(),byteArrayOutputStream);
        byte[] arr = byteArrayOutputStream.toByteArray();
        val read = ArrowConverter.readFromBytes(arr);
        assertEquals(recordsToWrite,read);

        //send file
        File tmp =  tmpDataFile(recordsToWrite);
        ArrowRecordReader recordReader = new ArrowRecordReader();

        recordReader.initialize(new FileSplit(tmp));

        recordReader.next();
        ArrowWritableRecordBatch currentBatch = recordReader.getCurrentBatch();
        INDArray arr2 = ArrowConverter.toArray(currentBatch);
        assertEquals(2,arr2.rows());
        assertEquals(2,arr2.columns());
    }


    @Test
    public void testSchemaConversionBasic() {
        Schema.Builder schemaBuilder = new Schema.Builder();
        for(int i = 0; i < 2; i++) {
            schemaBuilder.addColumnDouble("test-" + i);
            schemaBuilder.addColumnInteger("testi-" + i);
            schemaBuilder.addColumnLong("testl-" + i);
            schemaBuilder.addColumnFloat("testf-" + i);
        }


        Schema schema = schemaBuilder.build();
        val schema2 = ArrowConverter.toArrowSchema(schema);
        assertEquals(8,schema2.getFields().size());
        val convertedSchema = ArrowConverter.toDatavecSchema(schema2);
        assertEquals(schema,convertedSchema);
    }

    @Test
    public void testReadSchemaAndRecordsFromByteArray() throws Exception {
        BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);

        int valueCount = 3;
        List<Field> fields = new ArrayList<>();
        fields.add(ArrowConverter.field("field1",new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)));
        fields.add(ArrowConverter.intField("field2"));

        List<FieldVector> fieldVectors = new ArrayList<>();
        fieldVectors.add(ArrowConverter.vectorFor(allocator,"field1",new float[] {1,2,3}));
        fieldVectors.add(ArrowConverter.vectorFor(allocator,"field2",new int[] {1,2,3}));


        org.apache.arrow.vector.types.pojo.Schema schema = new org.apache.arrow.vector.types.pojo.Schema(fields);

        VectorSchemaRoot schemaRoot1 = new VectorSchemaRoot(schema, fieldVectors, valueCount);
        VectorUnloader vectorUnloader = new VectorUnloader(schemaRoot1);
        vectorUnloader.getRecordBatch();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try(ArrowFileWriter arrowFileWriter = new ArrowFileWriter(schemaRoot1,null,newChannel(byteArrayOutputStream))) {
            arrowFileWriter.writeBatch();
        } catch (IOException e) {
            e.printStackTrace();
        }

        byte[] arr = byteArrayOutputStream.toByteArray();
        val arr2 = ArrowConverter.readFromBytes(arr);
        assertEquals(2,arr2.getFirst().numColumns());
        assertEquals(3,arr2.getRight().size());

        val arrowCols = ArrowConverter.toArrowColumns(allocator,arr2.getFirst(),arr2.getRight());
        assertEquals(2,arrowCols.size());
        assertEquals(valueCount,arrowCols.get(0).getValueCount());
    }


    @Test
    public void testVectorForEdgeCases() {
        BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
        val vector = ArrowConverter.vectorFor(allocator,"field1",new float[]{Float.MIN_VALUE,Float.MAX_VALUE});
        assertEquals(Float.MIN_VALUE,vector.get(0),1e-2);
        assertEquals(Float.MAX_VALUE,vector.get(1),1e-2);

        val vectorInt = ArrowConverter.vectorFor(allocator,"field1",new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE});
        assertEquals(Integer.MIN_VALUE,vectorInt.get(0),1e-2);
        assertEquals(Integer.MAX_VALUE,vectorInt.get(1),1e-2);

    }

    @Test
    public void testVectorFor() {
        BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);

        val vector = ArrowConverter.vectorFor(allocator,"field1",new float[]{1,2,3});
        assertEquals(3,vector.getValueCount());
        assertEquals(1,vector.get(0),1e-2);
        assertEquals(2,vector.get(1),1e-2);
        assertEquals(3,vector.get(2),1e-2);

        val vectorLong = ArrowConverter.vectorFor(allocator,"field1",new long[]{1,2,3});
        assertEquals(3,vectorLong.getValueCount());
        assertEquals(1,vectorLong.get(0),1e-2);
        assertEquals(2,vectorLong.get(1),1e-2);
        assertEquals(3,vectorLong.get(2),1e-2);


        val vectorInt = ArrowConverter.vectorFor(allocator,"field1",new int[]{1,2,3});
        assertEquals(3,vectorInt.getValueCount());
        assertEquals(1,vectorInt.get(0),1e-2);
        assertEquals(2,vectorInt.get(1),1e-2);
        assertEquals(3,vectorInt.get(2),1e-2);

        val vectorDouble = ArrowConverter.vectorFor(allocator,"field1",new double[]{1,2,3});
        assertEquals(3,vectorDouble.getValueCount());
        assertEquals(1,vectorDouble.get(0),1e-2);
        assertEquals(2,vectorDouble.get(1),1e-2);
        assertEquals(3,vectorDouble.get(2),1e-2);


        val vectorBool = ArrowConverter.vectorFor(allocator,"field1",new boolean[]{true,true,false});
        assertEquals(3,vectorBool.getValueCount());
        assertEquals(1,vectorBool.get(0),1e-2);
        assertEquals(1,vectorBool.get(1),1e-2);
        assertEquals(0,vectorBool.get(2),1e-2);
    }

    @Test
    public void testRecordReaderAndWriteFile() throws Exception {
        val recordsToWrite = recordToWrite();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ArrowConverter.writeRecordBatchTo(recordsToWrite.getRight(),recordsToWrite.getFirst(),byteArrayOutputStream);
        byte[] arr = byteArrayOutputStream.toByteArray();
        val read = ArrowConverter.readFromBytes(arr);
        assertEquals(recordsToWrite,read);

        //send file
        File tmp =  tmpDataFile(recordsToWrite);
        RecordReader recordReader = new ArrowRecordReader();

        recordReader.initialize(new FileSplit(tmp));

        List<Writable> record = recordReader.next();
        assertEquals(2,record.size());

    }

    @Test
    public void testRecordReaderMetaDataList() throws Exception {
        val recordsToWrite = recordToWrite();
        //send file
        File tmp =  tmpDataFile(recordsToWrite);
        RecordReader recordReader = new ArrowRecordReader();
        RecordMetaDataIndex recordMetaDataIndex = new RecordMetaDataIndex(0,tmp.toURI(),ArrowRecordReader.class);
        recordReader.loadFromMetaData(Arrays.<RecordMetaData>asList(recordMetaDataIndex));

        Record record = recordReader.nextRecord();
        assertEquals(2,record.getRecord().size());



    }

    @Test
    public void testDates() {
        Date now = new Date();
        BufferAllocator bufferAllocator = new RootAllocator(Long.MAX_VALUE);
        TimeStampMilliVector timeStampMilliVector = ArrowConverter.vectorFor(bufferAllocator, "col1", new Date[]{now});
        assertEquals(now.getTime(),timeStampMilliVector.get(0));
    }


    @Test
    public void testRecordReaderMetaData() throws Exception {
        val recordsToWrite = recordToWrite();
        //send file
        File tmp =  tmpDataFile(recordsToWrite);
        RecordReader recordReader = new ArrowRecordReader();
        RecordMetaDataIndex recordMetaDataIndex = new RecordMetaDataIndex(0,tmp.toURI(),ArrowRecordReader.class);
        recordReader.loadFromMetaData(recordMetaDataIndex);

        Record record = recordReader.nextRecord();
        assertEquals(2,record.getRecord().size());
    }





    private File tmpDataFile(Pair<Schema,List<List<Writable>>> recordsToWrite) throws IOException {
        //send file
        File tmp = new File(System.getProperty("java.io.tmpdir"),"tmp-file-" + UUID.randomUUID().toString());
        tmp.mkdirs();
        File tmpFile = new File(tmp,"data.arrow");
        tmpFile.deleteOnExit();
        FileOutputStream bufferedOutputStream = new FileOutputStream(tmpFile);
        ArrowConverter.writeRecordBatchTo(recordsToWrite.getRight(),recordsToWrite.getFirst(),bufferedOutputStream);
        bufferedOutputStream.flush();
        bufferedOutputStream.close();
        return tmp;
    }

    private Pair<Schema,List<List<Writable>>> recordToWrite() {
        List<List<Writable>> records = new ArrayList<>();
        records.add(Arrays.<Writable>asList(new DoubleWritable(0.0),new DoubleWritable(0.0)));
        records.add(Arrays.<Writable>asList(new DoubleWritable(0.0),new DoubleWritable(0.0)));
        Schema.Builder schemaBuilder = new Schema.Builder();
        for(int i = 0; i < 2; i++) {
            schemaBuilder.addColumnFloat("col-" + i);
        }

        return Pair.of(schemaBuilder.build(),records);
    }




}
