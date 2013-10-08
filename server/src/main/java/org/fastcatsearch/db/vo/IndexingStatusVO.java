package org.fastcatsearch.db.vo;

import java.sql.Timestamp;

import org.fastcatsearch.db.mapper.IndexingResultMapper.ResultStatus;
import org.fastcatsearch.ir.common.IndexingType;

public class IndexingStatusVO {
	public int id;
	public String collectionId;
	public IndexingType type;
	public ResultStatus status;
	public int docSize;
	public int insertSize;
	public int updateSize;
	public int deleteSize;
	public boolean isScheduled;
	public Timestamp startTime;
	public Timestamp endTime;
	public int duration;

}
