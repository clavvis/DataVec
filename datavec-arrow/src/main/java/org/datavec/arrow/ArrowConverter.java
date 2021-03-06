package org.datavec.arrow;

import lombok.val;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.ipc.SeekableReadChannel;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel;
import org.datavec.api.transform.ColumnType;
import org.datavec.api.transform.metadata.*;
import org.datavec.api.transform.schema.Schema;
import org.datavec.api.writable.*;
import org.datavec.arrow.recordreader.ArrowWritableRecordBatch;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.exception.ND4JIllegalArgumentException;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.primitives.Pair;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static java.nio.channels.Channels.newChannel;

/**
 * Interop between datavec primitives and arrow.
 * This allows for datavec schemas and primitives
 * to be converted to the arrow format.
 *
 * @author Adam Gibson
 */
public class ArrowConverter {

    /**
     * Create an ndarray from a matrix.
     * The included batch must be all the same number of rows in order
     * to work. The reason for this is {@link INDArray} must be all the same dimensions.
     * Note that the input columns must also be numerical. If they aren't numerical already,
     * consider using an {@link org.datavec.api.transform.TransformProcess} to transform the data
     * output from {@link org.datavec.arrow.recordreader.ArrowRecordReader} in to the proper format
     * for usage with this method for direct conversion.
     *
     * @param arrowWritableRecordBatch the incoming batch. This is typically output from
     *                                 an {@link org.datavec.arrow.recordreader.ArrowRecordReader}
     * @return an {@link INDArray} representative of the input data
     */
    public static INDArray toArray(ArrowWritableRecordBatch arrowWritableRecordBatch) {
        List<FieldVector> columnVectors = arrowWritableRecordBatch.getList();
        Schema schema = arrowWritableRecordBatch.getSchema();
        for(int i = 0; i < schema.numColumns(); i++) {
            switch(schema.getType(i)) {
                case Integer:
                    break;
                case Float:
                    break;
                case Double:
                    break;
                case Long:
                    break;
                default:
                    throw new ND4JIllegalArgumentException("Illegal data type found for column " + schema.getName(i));
            }
        }

        int rows  = arrowWritableRecordBatch.getList().get(0).getValueCount();
        int cols = schema.numColumns();
        INDArray arr  = Nd4j.create(rows,cols);
        for(int i = 0; i < cols; i++) {
            switch(schema.getType(i)) {
                case Integer:
                    IntVector intVector = (IntVector) columnVectors.get(i);
                    DataBuffer buffer = Nd4j.createBuffer(intVector.getDataBuffer().unwrap().nioBuffer(), DataBuffer.Type.INT,cols);
                    arr.putColumn(i,Nd4j.create(buffer, new int[] {cols,1}));
                    break;
                case Float:
                    Float4Vector float4Vector = (Float4Vector) columnVectors.get(i);
                    DataBuffer floatBuffer = Nd4j.createBuffer(float4Vector.getDataBuffer().unwrap().nioBuffer(), DataBuffer.Type.FLOAT,cols);
                    arr.putColumn(i,Nd4j.create(floatBuffer, new int[] {cols,1}));

                    break;
                case Double:
                    Float8Vector float8Vector = (Float8Vector) columnVectors.get(i);
                    DataBuffer doubleBuffer = Nd4j.createBuffer(float8Vector.getDataBuffer().unwrap().nioBuffer(), DataBuffer.Type.DOUBLE,cols);
                    arr.putColumn(i,Nd4j.create(doubleBuffer, new int[] {cols,1}));

                    break;
                case Long:
                    BigIntVector bigIntVector = (BigIntVector) columnVectors.get(i);
                    DataBuffer longBuffer = Nd4j.createBuffer(bigIntVector.getDataBuffer().unwrap().nioBuffer(), DataBuffer.Type.LONG,cols);
                    arr.putColumn(i,Nd4j.create(longBuffer, new int[] {cols,1}));
                    break;
            }
        }

        return arr;
    }


    /**
     * Write the records to the given output stream
     * @param recordBatch the record batch to write
     * @param inputSchema the input schema
     * @param outputStream the output stream to write to
     */
    public static void writeRecordBatchTo(List<List<Writable>> recordBatch, Schema inputSchema,OutputStream outputStream) {
        BufferAllocator bufferAllocator = new RootAllocator(Long.MAX_VALUE);
        writeRecordBatchTo(bufferAllocator,recordBatch,inputSchema,outputStream);
    }

    /**
     * Write the records to the given output stream
     * @param recordBatch the record batch to write
     * @param inputSchema the input schema
     * @param outputStream the output stream to write to
     */
    public static void writeRecordBatchTo(BufferAllocator bufferAllocator ,List<List<Writable>> recordBatch, Schema inputSchema,OutputStream outputStream) {
       if(!(recordBatch instanceof ArrowWritableRecordBatch)) {
           val convertedSchema = toArrowSchema(inputSchema);
           val columns  = toArrowColumns(bufferAllocator,inputSchema,recordBatch);
           try(VectorSchemaRoot root = new VectorSchemaRoot(convertedSchema,columns,recordBatch.size());
               ArrowFileWriter writer = new ArrowFileWriter(root, providerForVectors(columns,convertedSchema.getFields()), newChannel(outputStream))) {
               writer.start();
               writer.writeBatch();
               writer.end();


           } catch (IOException e) {
               throw new IllegalStateException(e);
           }

       }
       else {
           val convertedSchema = toArrowSchema(inputSchema);
           val pair = toArrowColumns(bufferAllocator,inputSchema,recordBatch);
           try(VectorSchemaRoot root = new VectorSchemaRoot(convertedSchema.getFields(),pair,recordBatch.size());
               ArrowFileWriter writer = new ArrowFileWriter(root, providerForVectors(pair,convertedSchema.getFields()), newChannel(outputStream))) {
               writer.start();
               writer.writeBatch();
               writer.end();


           } catch (IOException e) {
               e.printStackTrace();
           }
       }

    }





    /**
     * Read a datavec schema and record set
     * from the given bytes (usually expected to be an arrow format file)
     * @param input the input to read
     * @return the associated datavec schema and record
     */
    public static Pair<Schema,ArrowWritableRecordBatch> readFromBytes(byte[] input) throws IOException {
        BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
        Schema retSchema = null;
        ArrowWritableRecordBatch ret = null;
        SeekableReadChannel channel = new SeekableReadChannel(new ByteArrayReadableSeekableByteChannel(input));
        ArrowFileReader reader = new ArrowFileReader(channel, allocator);
        reader.loadNextBatch();
        retSchema = toDatavecSchema(reader.getVectorSchemaRoot().getSchema());
        //load the batch
        VectorUnloader unloader = new VectorUnloader(reader.getVectorSchemaRoot());
        VectorLoader vectorLoader = new VectorLoader(reader.getVectorSchemaRoot());
        ArrowRecordBatch recordBatch = unloader.getRecordBatch();

        vectorLoader.load(recordBatch);
        ret = asDataVecBatch(recordBatch,retSchema,reader.getVectorSchemaRoot());
        ret.setUnloader(unloader);

        return Pair.of(retSchema,ret);

    }

    /**
     * Convert a data vec {@link Schema}
     * to an arrow {@link org.apache.arrow.vector.types.pojo.Schema}
     * @param schema the input schema
     * @return the schema for arrow
     */
    public static org.apache.arrow.vector.types.pojo.Schema toArrowSchema(Schema schema) {
        List<Field> fields = new ArrayList<>(schema.numColumns());
        for(int i = 0; i < schema.numColumns(); i++) {
            fields.add(getFieldForColumn(schema.getName(i),schema.getType(i)));
        }

        return new org.apache.arrow.vector.types.pojo.Schema(fields);
    }

    /**
     * Convert an {@link org.apache.arrow.vector.types.pojo.Schema}
     * to a datavec {@link Schema}
     * @param schema the input arrow schema
     * @return the equivalent datavec schema
     */
    public static Schema toDatavecSchema(org.apache.arrow.vector.types.pojo.Schema schema) {
        Schema.Builder schemaBuilder = new Schema.Builder();
        for (int i = 0; i < schema.getFields().size(); i++) {
            schemaBuilder.addColumn(metaDataFromField(schema.getFields().get(i)));
        }
        return schemaBuilder.build();
    }




    /**
     * Shortcut method for returning a field
     * given an arrow type and name
     * with no sub fields
     * @param name the name of the field
     * @param arrowType the arrow type of the field
     * @return the resulting field
     */
    public static Field field(String name,ArrowType arrowType) {
        return new Field(name,FieldType.nullable(arrowType), new ArrayList<Field>());
    }



    /**
     * Create a field given the input {@link ColumnType}
     * and name
     * @param name the name of the field
     * @param columnType the column type to add
     * @return
     */
    public static Field getFieldForColumn(String name,ColumnType columnType) {
        switch(columnType) {
            case Long: return field(name,new ArrowType.Int(64,false));
            case Integer: return field(name,new ArrowType.Int(32,false));
            case Double: return field(name,new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE));
            case Float: return field(name,new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE));
            case Boolean: return field(name, new ArrowType.Bool());
            case Categorical: return field(name,new ArrowType.Utf8());
            case Time: return field(name,new ArrowType.Date(DateUnit.MILLISECOND));
            case Bytes: return field(name,new ArrowType.Binary());
            case NDArray: return field(name,new ArrowType.Binary());
            default: throw new IllegalArgumentException("Column type invalid " + columnType);
        }
    }

    /**
     * Shortcut method for creating a double field
     * with 64 bit floating point
     * @param name the name of the field
     * @return the created field
     */
    public static Field doubleField(String name) {
        return getFieldForColumn(name, ColumnType.Double);
    }

    /**
     * Shortcut method for creating a double field
     * with 32 bit floating point
     * @param name the name of the field
     * @return the created field
     */
    public static Field floatField(String name) {
        return getFieldForColumn(name,ColumnType.Float);
    }

    /**
     * Shortcut method for creating a double field
     * with 32 bit integer field
     * @param name the name of the field
     * @return the created field
     */
    public static Field intField(String name) {
        return getFieldForColumn(name,ColumnType.Integer);
    }

    /**
     * Shortcut method for creating a long field
     * with 64 bit long field
     * @param name the name of the field
     * @return the created field
     */
    public static Field longField(String name) {
        return getFieldForColumn(name,ColumnType.Long);
    }

    /**
     *
     * @param name
     * @return
     */
    public static Field stringField(String name) {
        return getFieldForColumn(name,ColumnType.String);
    }

    /**
     * Shortcut
     * @param name
     * @return
     */
    public static Field booleanField(String name) {
        return getFieldForColumn(name,ColumnType.Boolean);
    }


    /**
     * Provide a value look up dictionary based on the
     * given set of input {@link FieldVector} s for
     * reading and writing to arrow streams
     * @param vectors the vectors to use as a lookup
     * @return the associated {@link DictionaryProvider} for the given
     * input {@link FieldVector} list
     */
    public static DictionaryProvider providerForVectors(List<FieldVector> vectors,List<Field> fields) {
        Dictionary[] dictionaries = new Dictionary[vectors.size()];
        for(int i = 0; i < vectors.size(); i++) {
            DictionaryEncoding dictionary = fields.get(i).getDictionary();
            if(dictionary == null) {
                dictionary = new DictionaryEncoding(i,true,null);
            }
            dictionaries[i] = new Dictionary(vectors.get(i), dictionary);
        }
        return  new DictionaryProvider.MapDictionaryProvider(dictionaries);
    }


    /**
     * Given a buffer allocator and datavec schema,
     * convert the passed in batch of records
     * to a set of arrow columns
     * @param bufferAllocator the buffer allocator to use
     * @param schema the schema to convert
     * @param dataVecRecord the data vec record batch to convert
     * @return the converted list of {@link FieldVector}
     */
    public static List<FieldVector> toArrowColumns(final BufferAllocator bufferAllocator, final Schema schema, List<List<Writable>> dataVecRecord) {
        int numRows = dataVecRecord.size();

        List<FieldVector> ret = new ArrayList<>(schema.numColumns());

        for(int i = 0; i < schema.numColumns(); i++) {
            switch (schema.getType(i)) {
                case Integer: ret.add(intVectorOf(bufferAllocator,schema.getName(i),numRows)); break;
                case Long: ret.add(longVectorOf(bufferAllocator,schema.getName(i),numRows)); break;
                case Double: ret.add(doubleVectorOf(bufferAllocator,schema.getName(i),numRows));
                case Float: ret.add(floatVectorOf(bufferAllocator,schema.getName(i),numRows)); break;
                case Boolean: ret.add(booleanVectorOf(bufferAllocator,schema.getName(i),numRows)); break;
                case String: ret.add(stringVectorOf(bufferAllocator,schema.getName(i),numRows)); break;
                case Categorical: ret.add(stringVectorOf(bufferAllocator,schema.getName(i),numRows)); break;
                case Time: ret.add(timeVectorOf(bufferAllocator,schema.getName(i),numRows)); break;
                default: throw new IllegalArgumentException("Illegal type found " + schema.getType(i));

            }
        }

        for(int j = 0; j < schema.numColumns(); j++) {
            FieldVector fieldVector = ret.get(j);
            int row = 0;
            for(List<Writable> record : dataVecRecord) {
                Writable writable = record.get(j);
                switch (schema.getType(j)) {
                    case Integer:
                        IntVector intVector = (IntVector) fieldVector;
                        int set = writable.toInt();
                        intVector.set(row++,set);
                        break;
                    case Float:
                        Float4Vector float4Vector = (Float4Vector) fieldVector;
                        float set2 = writable.toFloat();
                        float4Vector.set(row++,set2);
                        break;
                    case Double:
                        double set3 = writable.toDouble();
                        Float8Vector float8Vector = (Float8Vector) fieldVector;
                        float8Vector.set(row++,set3);
                        break;
                    case Long:
                        BigIntVector largeIntVector = (BigIntVector) fieldVector;
                        largeIntVector.set(row++,writable.toLong());
                        break;
                    case Categorical:
                    case String:
                        String stringSet = writable.toString();
                        VarCharVector textVector = (VarCharVector) fieldVector;
                        textVector.set(row++,stringSet.getBytes());
                        break;
                    case Time:
                        int timeSet = writable.toInt();
                        TimeMilliVector timeMilliVector = (TimeMilliVector) fieldVector;
                        timeMilliVector.set(row++,timeSet);
                        break;

                }
            }

        }

        return ret;
    }





    /**
     *
     * @param allocator
     * @param name
     * @param data
     * @return
     */
    public static TimeStampMilliVector vectorFor(BufferAllocator allocator,String name,Date[] data) {
        TimeStampMilliVector float4Vector = new TimeStampMilliVector(name,allocator);
        float4Vector.allocateNew(data.length);
        for(int i = 0; i < data.length; i++) {
            float4Vector.setSafe(i,data[i].getTime());
        }

        float4Vector.setValueCount(data.length);

        return float4Vector;
    }


    /**
     *
     * @param allocator
     * @param name
     * @param length the length of the vector
     * @return
     */
    public static TimeStampMilliVector timeVectorOf(BufferAllocator allocator,String name,int length) {
        TimeStampMilliVector float4Vector = new TimeStampMilliVector(name,allocator);
        float4Vector.allocateNew(length);
        float4Vector.setValueCount(length);
        return float4Vector;
    }





    /**
     *
     * @param allocator
     * @param name
     * @param data
     * @return
     */
    public static VarCharVector vectorFor(BufferAllocator allocator,String name,String[] data) {
        VarCharVector float4Vector = new VarCharVector(name,allocator);
        float4Vector.allocateNew();
        for(int i = 0; i < data.length; i++) {
            float4Vector.setSafe(i,data[i].getBytes());
        }

        float4Vector.setValueCount(data.length);

        return float4Vector;
    }


    /**
     *
     * @param allocator
     * @param name
     * @param length the length of the vector
     * @return
     */
    public static VarCharVector stringVectorOf(BufferAllocator allocator,String name,int length) {
        VarCharVector float4Vector = new VarCharVector(name,allocator);
        float4Vector.allocateNew();
        float4Vector.setValueCount(length);
        return float4Vector;
    }



    /**
     *
     * @param allocator
     * @param name
     * @param data
     * @return
     */
    public static Float4Vector vectorFor(BufferAllocator allocator,String name,float[] data) {
        Float4Vector float4Vector = new Float4Vector(name,allocator);
        float4Vector.allocateNew(data.length);
        for(int i = 0; i < data.length; i++) {
            float4Vector.setSafe(i,data[i]);
        }

        float4Vector.setValueCount(data.length);

        return float4Vector;
    }


    /**
     *
     * @param allocator
     * @param name
     * @param length the length of the vector
     * @return
     */
    public static Float4Vector floatVectorOf(BufferAllocator allocator,String name,int length) {
        Float4Vector float4Vector = new Float4Vector(name,allocator);
        float4Vector.allocateNew(length);
        float4Vector.setValueCount(length);
        return float4Vector;
    }

    /**
     *
     * @param allocator
     * @param name
     * @param data
     * @return
     */
    public static Float8Vector vectorFor(BufferAllocator allocator,String name,double[] data) {
        Float8Vector float8Vector = new Float8Vector(name,allocator);
        float8Vector.allocateNew(data.length);
        for(int i = 0; i < data.length; i++) {
            float8Vector.setSafe(i,data[i]);
        }


        float8Vector.setValueCount(data.length);

        return float8Vector;
    }

    /**
     *
     * @param allocator
     * @param name
     * @param length the length of the vector
     * @return
     */
    public static Float8Vector doubleVectorOf(BufferAllocator allocator,String name,int length) {
        Float8Vector float8Vector = new Float8Vector(name,allocator);
        float8Vector.allocateNew();
        float8Vector.setValueCount(length);
        return float8Vector;
    }





    /**
     *
     * @param allocator
     * @param name
     * @param data
     * @return
     */
    public static BitVector vectorFor(BufferAllocator allocator,String name,boolean[] data) {
        BitVector float8Vector = new BitVector(name,allocator);
        float8Vector.allocateNew(data.length);
        for(int i = 0; i < data.length; i++) {
            float8Vector.setSafe(i,data[i] ? 1 : 0);
        }

        float8Vector.setValueCount(data.length);

        return float8Vector;
    }

    /**
     *
     * @param allocator
     * @param name
     * @return
     */
    public static BitVector booleanVectorOf(BufferAllocator allocator,String name,int length) {
        BitVector float8Vector = new BitVector(name,allocator);
        float8Vector.allocateNew(length);
        float8Vector.setValueCount(length);
        return float8Vector;
    }


    /**
     *
     * @param allocator
     * @param name
     * @param data
     * @return
     */
    public static IntVector vectorFor(BufferAllocator allocator,String name,int[] data) {
        IntVector float8Vector = new IntVector(name,FieldType.nullable(new ArrowType.Int(32,true)),allocator);
        float8Vector.allocateNew(data.length);
        for(int i = 0; i < data.length; i++) {
            float8Vector.setSafe(i,data[i]);
        }

        float8Vector.setValueCount(data.length);

        return float8Vector;
    }

    /**
     *
     * @param allocator
     * @param name
     * @return
     */
    public static IntVector intVectorOf(BufferAllocator allocator,String name,int length) {
        IntVector float8Vector = new IntVector(name,FieldType.nullable(new ArrowType.Int(32,true)),allocator);
        float8Vector.allocateNew(length);

        float8Vector.setValueCount(length);

        return float8Vector;
    }




    /**
     *
     * @param allocator
     * @param name
     * @param data
     * @return
     */
    public static BigIntVector vectorFor(BufferAllocator allocator,String name,long[] data) {
        BigIntVector float8Vector = new BigIntVector(name,FieldType.nullable(new ArrowType.Int(64,true)),allocator);
        float8Vector.allocateNew(data.length);
        for(int i = 0; i < data.length; i++) {
            float8Vector.setSafe(i,data[i]);
        }

        float8Vector.setValueCount(data.length);

        return float8Vector;
    }



    /**
     *
     * @param allocator
     * @param name
     * @param length the number of rows in the column vector
     * @return
     */
    public static BigIntVector longVectorOf(BufferAllocator allocator,String name,int length) {
        BigIntVector float8Vector = new BigIntVector(name,FieldType.nullable(new ArrowType.Int(64,true)),allocator);
        float8Vector.allocateNew(length);
        float8Vector.setValueCount(length);
        return float8Vector;
    }

    private static ColumnMetaData metaDataFromField(Field field) {
        ArrowType arrowType = field.getFieldType().getType();
        if(arrowType instanceof ArrowType.Int) {
            val intType = (ArrowType.Int) arrowType;
            if(intType.getBitWidth() == 32)
                return new IntegerMetaData(field.getName());
            else {
                return new LongMetaData(field.getName());
            }
        }
        else if(arrowType instanceof ArrowType.Bool) {
            return new BooleanMetaData(field.getName());
        }
        else if(arrowType  instanceof ArrowType.FloatingPoint) {
            val floatingPointType = (ArrowType.FloatingPoint) arrowType;
            if(floatingPointType.getPrecision() == FloatingPointPrecision.DOUBLE)
                return new DoubleMetaData(field.getName());
            else {
                return new FloatMetaData(field.getName());
            }
        }
        else if(arrowType instanceof  ArrowType.Binary) {
            return new BinaryMetaData(field.getName());
        }
        else if(arrowType instanceof ArrowType.Utf8) {
            return new StringMetaData(field.getName());

        }
        else if(arrowType instanceof ArrowType.Date) {
            return new TimeMetaData(field.getName());
        }
        else {
            throw new IllegalStateException("Illegal type " + field.getFieldType().getType());
        }

    }


    /**
     * Based on an input {@link ColumnType}
     * get an entry from a {@link FieldVector}
     *
     * @param item the row of the item to get from the column vector
     * @param from the column vector from
     * @param columnType the column type
     * @return the resulting writable
     */
    public static Writable fromEntry(int item,FieldVector from,ColumnType columnType) {
        if(from.getValueCount() < item) {
            throw new IllegalArgumentException("Index specified greater than the number of items in the vector with length " + from.getValueCount());
        }

        switch(columnType) {
            case Integer:
                UInt4Vector intVector = (UInt4Vector) from;
                return new IntWritable(intVector.get(item));
            case Long:
                UInt8Vector intVector1 = (UInt8Vector) from;
                return new LongWritable(intVector1.get(item));
            case Float:
                Float4Vector float4Vector = (Float4Vector) from;
                return new FloatWritable(float4Vector.get(item));
            case Double:
                Float8Vector float8Vector = (Float8Vector) from;
                return new DoubleWritable(float8Vector.get(item));
            case Boolean:
                BitVector bitVector = (BitVector) from;
                return new BooleanWritable(bitVector.get(item) > 0);
            case Categorical:
                VarCharVector varCharVector = (VarCharVector) from;
                return new Text(varCharVector.get(item));
            case String:
                VarCharVector varCharVector2 = (VarCharVector) from;
                return new Text(varCharVector2.get(item));
            case Time:
                //TODO: need to look at closer
                TimeStampMilliVector timeStampMilliVector = (TimeStampMilliVector) from;
                return new LongWritable(timeStampMilliVector.get(item));
            default:
                throw new IllegalArgumentException("Illegal type " + from.getClass().getName());
        }
    }



    private static ArrowWritableRecordBatch asDataVecBatch(ArrowRecordBatch arrowRecordBatch, Schema schema, VectorSchemaRoot vectorLoader) {
        //iterate column wise over the feature vectors, returning entries
        List<FieldVector> fieldVectors = new ArrayList<>();
        for(int j = 0; j < schema.numColumns(); j++) {
            String name = schema.getName(j);
            FieldVector fieldVector = vectorLoader.getVector(name);
            fieldVectors.add(fieldVector);
        }

        ArrowWritableRecordBatch ret = new ArrowWritableRecordBatch(fieldVectors, schema);
        ret.setArrowRecordBatch(arrowRecordBatch);

        return ret;
    }



}
