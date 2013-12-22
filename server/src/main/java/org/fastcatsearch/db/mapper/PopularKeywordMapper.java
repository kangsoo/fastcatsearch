package org.fastcatsearch.db.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.fastcatsearch.db.vo.PopularKeywordVO;

/*
 * 인기키워드 테이블.
 * int id가 자동증가 pk이다. 
 * */
public interface PopularKeywordMapper extends ManagedMapper {
	
	public List<PopularKeywordVO> getEntryList(@Param("category") String category, @Param("time") String time) throws Exception;
	
	public void putEntry(PopularKeywordVO vo) throws Exception;
	
	public void deleteEntry(@Param("id") int id);
	
}