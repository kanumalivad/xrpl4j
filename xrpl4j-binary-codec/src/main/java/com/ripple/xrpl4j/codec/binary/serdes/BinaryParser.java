package com.ripple.xrpl4j.codec.binary.serdes;

import com.google.common.primitives.UnsignedLong;
import com.ripple.xrpl4j.codec.binary.ByteUtils;
import com.ripple.xrpl4j.codec.binary.FieldHeader;
import com.ripple.xrpl4j.codec.binary.UnsignedByte;
import com.ripple.xrpl4j.codec.binary.definitions.DefinitionsService;
import com.ripple.xrpl4j.codec.binary.enums.FieldInstance;
import com.ripple.xrpl4j.codec.binary.types.FieldWithValue;
import com.ripple.xrpl4j.codec.binary.types.SerializedType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

public class BinaryParser {

  // 1 byte encodes to 2 characters in hex
  private static final int BYTE_HEX_LENGTH = 2;

  private final String hex;

  private int cursor = 0;

  public BinaryParser(String hex) {
    this.hex = hex;
  }

  public UnsignedByte peek() {
    return UnsignedByte.of(hex.substring(cursor, cursor + BYTE_HEX_LENGTH));
  }

  public void skip(int bytesToSkip) {
    cursor += bytesToSkip * BYTE_HEX_LENGTH;
  }

  public List<UnsignedByte> read(int bytesToRead) {
    if (cursor >= hex.length()) {
      throw new IndexOutOfBoundsException("cursor moved past end of buffer");
    }
    List<UnsignedByte> result = new ArrayList<>();
    for (int i = 0; i < bytesToRead; i++) {
      result.add(peek());
      skip(1);
    }
    return result;
  }

  public UnsignedLong readUInt8() {
    return readUInt(1);
  }

  public UnsignedLong readUInt16() {
    return readUInt(2);
  }

  public UnsignedLong readUInt32() {
    return readUInt(4);
  }

  public UnsignedLong readUInt64() {
    return readUInt(8);
  }

  public int size() {
    return hex.length() / BYTE_HEX_LENGTH;
  }

  public boolean end() {
    return cursor >= hex.length();
  }

  /**
   * Reads the length of the variable length encoded bytes
   *
   * @return The length of the variable length encoded bytes
   */
  public int readVariableLengthLength() {
    int b1 = this.readUInt8().intValue();
    if (b1 <= 192) {
      return b1;
    } else if (b1 <= 240) {
      int b2 = this.readUInt8().intValue();
      return 193 + (b1 - 193) * 256 + b2;
    } else if (b1 <= 254) {
      int b2 = this.readUInt8().intValue();
      int b3 = this.readUInt8().intValue();
      return 12481 + (b1 - 241) * 65536 + b2 * 256 + b3;
    }
    throw new Error("Invalid variable length indicator");
  }

  /**
   * Reads the field ordinal from the BinaryParser
   *
   * @return Field ordinal
   */
  public FieldHeader readFieldHeader() {
    int type = this.readUInt8().intValue();
    int nth = type & 15;
    type >>= 4;

    if (type == 0) {
      type = this.readUInt8().intValue();
      if (type == 0 || type < 16) {
        throw new Error("Cannot read FieldOrdinal, type_code out of range");
      }
    }

    if (nth == 0) {
      nth = this.readUInt8().intValue();
      if (nth == 0 || nth < 16) {
        throw new Error("Cannot read FieldOrdinal, field_code out of range");
      }
    }

    return FieldHeader.builder().fieldCode(nth).typeCode(type).build();
  }

  /**
   * Read the field from the BinaryParser
   *
   * @return The field represented by the bytes at the head of the BinaryParser
   */
  public Optional<FieldInstance> readField() {
    FieldHeader header = readFieldHeader();
    String fieldName = DefinitionsService.getInstance().getFieldName(header);
    return DefinitionsService.getInstance().getFieldInstance(fieldName);
  }

  /**
   * Read a given type from the BinaryParser
   *
   * @param type The type that you want to read from the BinaryParser
   * @return The instance of that type read from the BinaryParser
   */
  public <T extends SerializedType<T>> T readType(Class<T> type) {
    try {
      return type.getDeclaredConstructor().newInstance().fromParser(this, OptionalInt.empty());
    } catch (Exception e) {
      throw new RuntimeException("could not instantiate field of type " + type.getName(), e);
    }
  }

  /**
   * Get the type associated with a given field
   *
   * @param field The field that you wan to get the type of
   * @return The type associated with the given field
   */
  public SerializedType typeForField(FieldInstance field) {
    return SerializedType.getTypeByName(field.type());
  }


  /**
   * Read value of the type specified by field from the BinaryParser
   *
   * @param field The field that you want to get the associated value for
   * @return The value associated with the given field
   */
  public SerializedType readFieldValue(FieldInstance field) {
    SerializedType type = this.typeForField(field);
    if (type == null) {
      throw new IllegalArgumentException("unsupported type " + type);
    }
    OptionalInt sizeHint = field.isVariableLengthEncoded()
        ? OptionalInt.of(this.readVariableLengthLength())
        : OptionalInt.empty();

    try {
      return type.fromParser(this, sizeHint);
    } catch (Exception e) {
      throw new RuntimeException("could not instantiate field of type " + field.name(), e);
    }
  }

  /**
   * Get the next field and value from the BinaryParser
   *
   * @return The field and value
   */
  public Optional<FieldWithValue> readFieldAndValue() {
    return this.readField()
        .map(field ->
            FieldWithValue.builder()
                .field(field)
                .value(this.readFieldValue(field))
                .build());
  }

  private UnsignedLong readUInt(int bytes) {
    return ByteUtils.coalesceToUnsignedLong(read(bytes));
  }


}
