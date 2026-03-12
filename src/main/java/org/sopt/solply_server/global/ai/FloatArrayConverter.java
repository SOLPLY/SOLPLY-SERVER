package org.sopt.solply_server.global.ai;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
        if (dbData.length % Float.BYTES != 0) {
            log.warn("임베딩 데이터 손상 감지: byte 길이가 4의 배수가 아닙니다. length={}", dbData.length);
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(dbData).order(ByteOrder.LITTLE_ENDIAN);
        float[] arr = new float[dbData.length / Float.BYTES];
        for (int i = 0; i < arr.length; i++) arr[i] = buf.getFloat();
        return arr;
    }
}
