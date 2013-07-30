/*
 * Copyright 2013 Websquared, Inc.
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

package org.fastcatsearch.ir.document;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.zip.Deflater;

import org.apache.commons.io.FileUtils;
import org.fastcatsearch.ir.common.IRException;
import org.fastcatsearch.ir.common.IndexFileNames;
import org.fastcatsearch.ir.config.IndexConfig;
import org.fastcatsearch.ir.field.Field;
import org.fastcatsearch.ir.io.BitSet;
import org.fastcatsearch.ir.io.BufferedFileInput;
import org.fastcatsearch.ir.io.BufferedFileOutput;
import org.fastcatsearch.ir.io.BytesDataOutput;
import org.fastcatsearch.ir.io.IndexInput;
import org.fastcatsearch.ir.settings.FieldSetting;
import org.fastcatsearch.ir.settings.PkRefSetting;
import org.fastcatsearch.ir.settings.PrimaryKeySetting;
import org.fastcatsearch.ir.settings.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 2013-6-13 문서를 한개씩 압축하여 기록한다. block갯수만큼 모아서 압축하는 방식은 없어짐.
 * 입력되는 Document들을 파일로 기록하는 클래스.
 * 
 * 생성되는파일은 4개인데 문서데이터파일, 데이터파일내 문서위치를 기록한 문서위치파일, 삭제문서파일 그리고 PrimaryKey맵파일이다.
 * pk맵파일은 pk와 내부문서번호를 맵핑시킨파일인데, 다음번 색인시 추가되는 pk를 이 파일에서 찾아, 삭제문서리스트에 추가해야한다.
 * 
 * 문서번호는 0번부터 생성한다. 차후에 DocumentReader에서 읽을때는 읽으려는 문서번호에서 docbaseNo을 빼서 내부문서번호로
 * 변경한뒤 읽는다.
 * 
 * @author sangwook.song
 * 
 */
public class DocumentWriter {
	private static Logger logger = LoggerFactory.getLogger(DocumentWriter.class);

	private List<FieldSetting> fields;
	private BufferedFileOutput docOutput;
	private BufferedFileOutput positionOutput;
	private byte[] workingBuffer;
	private BytesDataOutput fbaos;
	private BytesDataOutput pkbaos;
	private BitSet deleteSet;
	private int localDocNo;
	private PrimaryKeyIndexWriter pkIndexWriter;
	private Deflater compressor;
	private File dir;
	private int indexInterval;
	private int count;
	private int updateDocCount;
	private PrimaryKeySetting primaryKeySetting;
	private int[] primaryKeyFieldIdList;

	public DocumentWriter(Schema schema, File dir, IndexConfig indexConfig) throws IOException, IRException {
		this(schema, dir, 0, indexConfig);
	}

	public DocumentWriter(Schema schema, File dir, int revision, IndexConfig indexConfig) throws IOException, IRException {
		this.dir = dir;
		
		boolean isAppend = revision > 0;
		String segmentId = dir.getName();
		File revisionDir = IndexFileNames.getRevisionDir(dir, revision);
		
		compressor = new Deflater(Deflater.BEST_SPEED);
		fields = schema.schemaSetting().getFieldSettingList();
		
		primaryKeySetting = schema.schemaSetting().getPrimaryKeySetting();
		
		if (primaryKeySetting != null &&  primaryKeySetting.getFieldList() != null) {
			List<PkRefSetting> refList = primaryKeySetting.getFieldList();
			
			primaryKeyFieldIdList = new int[refList.size()];
			
			int sequence = 0;
			for (PkRefSetting refSetting : refList) {
				String fieldId = refSetting.getRef();
				primaryKeyFieldIdList[sequence] = schema.getFieldSequence(fieldId);
				logger.debug("pk field [{}]{} >> {}", sequence, fieldId, primaryKeyFieldIdList[sequence]);
				sequence++;
			}
		}
		
		docOutput = new BufferedFileOutput(dir, IndexFileNames.docStored, isAppend);
		positionOutput = new BufferedFileOutput(dir, IndexFileNames.docPosition, isAppend);

		indexInterval = indexConfig.getPkTermInterval();
		int bucketSize = indexConfig.getPkBucketSize();

		//
		// 증분수집일 경우, pk를 나중에 쉽게 읽을 수 있도록 임시벌크파일 형태로 기록한다.
		//
		if (isAppend) {
			pkIndexWriter = new PrimaryKeyIndexWriter(revisionDir, IndexFileNames.getTempFileName(IndexFileNames.primaryKeyMap), indexInterval, bucketSize);
		} else {
			// 전체색인의 경우는 이후에 다시 작업할 일이 없으므로, 완전한 pk map파일로 기록한다.
			pkIndexWriter = new PrimaryKeyIndexWriter(revisionDir, IndexFileNames.primaryKeyMap, indexInterval, bucketSize);
		}

		fbaos = new BytesDataOutput(3 * 1024 * 1024); //초기 3Mb로 시작.
		pkbaos = new BytesDataOutput(1024); //초기 1kb로 시작.
		workingBuffer = new byte[1024];

		String docDeleteSetName = IndexFileNames.getSuffixFileName(IndexFileNames.docDeleteSet, segmentId);
		if (isAppend) {
			File prevRevisionDir = IndexFileNames.getRevisionDir(dir, revision - 1);
			// copy prev revision's delete.set
			// 증분색인의 append일 경우에는 이전 revision의 deleteSet을 가져와서 사용한다.
			// DocumentWriter.close()시 이전 rev와 새 rev의 중복되는 문서를
			// delete처리해준다.
//			File prevDelete = new File(IRFileName.getRevisionDir(dir, revision - 1), IRFileName.docDeleteSet);
			File prevDelete = new File(prevRevisionDir, docDeleteSetName);
			//이전 리비전의 delete.set을 현재 리비전 dir로 가져와서 이어쓰도록 한다.
			FileUtils.copyFileToDirectory(prevDelete, revisionDir);
			deleteSet = new BitSet(revisionDir, docDeleteSetName);
			
			IndexInput docInput = new BufferedFileInput(dir, IndexFileNames.docStored);
			localDocNo = docInput.readInt();
			docInput.close();
		} else {
			deleteSet = new BitSet(revisionDir, docDeleteSetName, true);

			docOutput.writeInt(0); // document count
		}

	}

	public int getUpdateDocCount() {
		return updateDocCount;
	}

	public int write(Document document) throws IOException, IRException {
		fbaos.reset();
		long docStartPosition = docOutput.position();
		positionOutput.writeLong(docStartPosition);
		
		for (int i = 0; i < document.size(); i++) {
			Field f = document.get(i);
			//필드가 null이면 데이터없는 것으로 처리
			
			if(f == null || f.isNull() || !fields.get(i).isStore()){
				fbaos.writeBoolean(false);
			} else {
				fbaos.writeBoolean(true);
				f.writeRawTo(fbaos);
			}
		}
		
		if(primaryKeyFieldIdList != null){
			pkbaos.reset();
			for (int fieldId : primaryKeyFieldIdList) {
				Field f = document.get(fieldId);
				if(f == null || f.isNull()){
					throw new IOException("PK field value cannot be null. fieldId="+fieldId+", field="+f);
				} else {
					f.writeFixedDataTo(pkbaos);
				}
				logger.debug("PK >> {}", f);
			}
			
			int preDocNo = pkIndexWriter.put(pkbaos.array(), 0, (int) pkbaos.position(), localDocNo);
			// logger.trace("document doc no = "+localDocNo);
			if (preDocNo >= 0) {
				 logger.debug("DUP delete >> {}", preDocNo);
				deleteSet.set(preDocNo);
				updateDocCount++;// 수집시 데이터내에 서로 중복된 문서가 발견된 경우 count증가.
			}
		}
		
		compressor.reset();
		compressor.setInput(fbaos.array(), 0, (int) fbaos.position());
		compressor.finish();

		long pos = docOutput.position();
		docOutput.writeInt(0); // 압축데이터길이 임시기록.

		int compressedDataLength = 0;
		while (!compressor.finished()) {
			int count = compressor.deflate(workingBuffer);
			docOutput.writeBytes(workingBuffer, 0, count);
			compressedDataLength += count;
		}
		
		long lastPos = docOutput.position();
		// 길이헤더를 정확한 데이터로 기록한다.
		docOutput.seek(pos);
		docOutput.writeInt(compressedDataLength);
		docOutput.seek(lastPos);
		
		count++;
		return localDocNo++;
	}

	public void close() throws IOException {
		logger.debug("DocumentWriter close() count={}, pk={}", count, pkIndexWriter.count());

		// write header
		docOutput.seek(0);
		docOutput.writeInt(localDocNo);

		docOutput.close();
		positionOutput.close();
		pkIndexWriter.write();
		pkIndexWriter.close();

		if (count <= 0) {
			return;
		}

		// save delete list
		deleteSet.save();
	}

}
