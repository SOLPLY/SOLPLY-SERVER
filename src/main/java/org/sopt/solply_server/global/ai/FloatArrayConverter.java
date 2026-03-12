package org.sopt.solply_server.global.ai;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Converter
public class FloatArrayConverter implements AttributeConverter<float[], byte[]> {

    @Override
    public byte[] convertToDatabaseColumn(float[] attribute) {
        if (attribute == null) return null;
        ByteBuffer buf = ByteBuffer.allocate(attribute.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : attribute) buf.putFloat(f);
        return buf.array();
    }

    @Override
    public float[] convertToEntityAttribute(byte[] dbData) {
        if (dbData == null) return null;
        ByteBuffer buf = ByteBuffer.wrap(dbData).order(ByteOrder.LITTLE_ENDIAN);
        float[] arr = new float[dbData.length / Float.BYTES];
        for (int i = 0; i < arr.length; i++) arr[i] = buf.getFloat();
        return arr;
    }
}
