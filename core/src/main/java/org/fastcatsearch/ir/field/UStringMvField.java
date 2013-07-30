package org.fastcatsearch.ir.field;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.fastcatsearch.ir.io.CharVector;
import org.fastcatsearch.ir.io.DataInput;
import org.fastcatsearch.ir.io.DataOutput;

public class UStringMvField extends UStringField implements MultiValueFieldType {

	public UStringMvField(String id) {
		super(id);
		multiValue = true;
	}

	public UStringMvField(String id, int size) {
		super(id, size);
		multiValue = true;
	}

	public UStringMvField(String id, String data, int size) {
		super(id, data, size);
		multiValue = true;
	}
	
	@Override
	public void readFrom(DataInput input) throws IOException {
		int multiValueCount = input.readVInt();
		fieldsData = new ArrayList<CharVector>(multiValueCount);
		for (int i = 0; i < multiValueCount; i++) {
			char[] chars = input.readUString();
			((List<CharVector>) fieldsData).add(new CharVector(chars, 0, chars.length));
		}
	}

	@Override
	public void writeTo(DataOutput output) throws IOException {
		List<CharVector> list = (List<CharVector>) fieldsData;
		int multiValueCount = list.size();
		output.writeVInt(multiValueCount);
		// 고정길이인지 가변길이인지
		if (size > 0) {
			// 고정길이
			for (int i = 0; i < list.size(); i++) {
				CharVector charVector = (CharVector) list.get(i);
				output.writeVInt(size);
				output.writeUChars(charVector.array, charVector.start, size);
				if (charVector.length < size) {
					for (int j = 0; j < size - charVector.length; j++) {
						output.writeUChar(0);
					}
				}
			}
		} else {
			// 가변길이
			for (int i = 0; i < multiValueCount; i++) {
				CharVector charVector = (CharVector) list.get(i);
				output.writeUString(charVector.array, charVector.start, charVector.length);
			}
		}

	}

	@Override
	public void writeFixedDataTo(DataOutput output) throws IOException {
		// multi value필드는 데이터가 없으면 기록하지 않는다.
		if (fieldsData == null) {
			return;
		}

		if (size > 0) {
			List<CharVector> list = (List<CharVector>) fieldsData;
			for (int i = 0; i < list.size(); i++) {
				CharVector charVector = (CharVector) list.get(i);
				output.writeUChars(charVector.array, charVector.start, size);
				if (charVector.length < size) {
					for (int j = 0; j < size - charVector.length; j++) {
						output.writeUChar(0);
					}
				}
			}
		} else {
			throw new IOException("가변길이필드는 지원하지 않습니다.");
		}
	}

	@Override
	public final void writeDataTo(DataOutput output) throws IOException {
		if (size > 0) {
			writeFixedDataTo(output);
		} else {
			List<CharVector> list = (List<CharVector>) fieldsData;
			for (int i = 0; i < list.size(); i++) {
				CharVector charVector = (CharVector) list.get(i);
				output.writeUChars(charVector.array, charVector.start, charVector.length);
			}
		}

	}

	@Override
	public FieldDataWriter getDataWriter() throws IOException {
		final List<CharVector> list = (List<CharVector>) fieldsData;
		return new FieldDataWriter(list) {
			@Override
			protected void writeEachData(Object object, DataOutput output) throws IOException {
				CharVector charVector = (CharVector) object;
				if (size > 0) {

					output.writeUChars(charVector.array, charVector.start, size);
					if (charVector.length < size) {
						for (int j = 0; j < size - charVector.length; j++) {
							output.writeUChar(0);
						}
					}
				} else {
					// 가변길이.
					output.writeUChars(charVector.array, charVector.start, charVector.length);
				}
			}
		};
	}

//	@Override
//	public String toString() {
//		StringBuilder sb = new StringBuilder();
//		final List<CharVector> list = (List<CharVector>) fieldsData;
//		if (list != null) {
//			for (int i = 0; i < list.size(); i++) {
//				CharVector charVector = (CharVector) list.get(i);
//				sb.append(charVector.toString());
//				if (i < list.size() - 1) {
//					sb.append(DEFAULT_MULTI_VALUE_DELIMITER);
//				}
//			}
//		}else{
//			return null;
//		}
//		return sb.toString();
//	}
}
