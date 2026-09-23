package ru.yandex.practicum.kafka.serializer;

import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class AvroSerializer implements Serializer<SpecificRecordBase> {

    @Override
    public byte[] serialize(String topic, SpecificRecordBase data) {
        if (data == null) {
            return null;
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            SpecificDatumWriter<SpecificRecordBase> writer =
                    new SpecificDatumWriter<>(data.getSchema());

            BinaryEncoder encoder =
                    EncoderFactory.get().binaryEncoder(output, null);

            writer.write(data, encoder);
            encoder.flush();

            return output.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new SerializationException(
                    "Не удалось сериализовать Avro-событие для топика " + topic, e);
        }
    }
}