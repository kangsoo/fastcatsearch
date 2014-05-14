package org.fastcatsearch.ir.search.clause;

import java.io.IOException;
import java.util.Iterator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.AnalyzerOption;
import org.apache.lucene.analysis.tokenattributes.AdditionalTermAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.CharsRefTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.StopwordAttribute;
import org.apache.lucene.analysis.tokenattributes.SynonymAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.CharsRef;
import org.fastcatsearch.ir.io.CharVector;
import org.fastcatsearch.ir.query.HighlightInfo;
import org.fastcatsearch.ir.query.RankInfo;
import org.fastcatsearch.ir.query.Term;
import org.fastcatsearch.ir.query.Term.Option;
import org.fastcatsearch.ir.query.Term.Type;
import org.fastcatsearch.ir.search.PostingReader;
import org.fastcatsearch.ir.search.SearchIndexReader;
import org.fastcatsearch.ir.search.method.NormalSearchMethod;
import org.fastcatsearch.ir.search.method.SearchMethod;
import org.fastcatsearch.ir.settings.IndexSetting;
import org.fastcatsearch.ir.settings.RefSetting;

public class BooleanClause extends OperatedClause {

	private String termString;
	private OperatedClause operatedClause;

	public BooleanClause(SearchIndexReader searchIndexReader, Term term, HighlightInfo highlightInfo) {
		this(searchIndexReader, term, highlightInfo, null);
	}
	public BooleanClause(SearchIndexReader searchIndexReader, Term term, HighlightInfo highlightInfo, String requestTypeAttribute) {
		super(searchIndexReader.indexId());
		String indexId = searchIndexReader.indexId();
		String termString = term.termString();
		this.termString = termString;
		float weight = term.weight();
		Option searchOption = term.option();
		CharVector fullTerm = new CharVector(termString);
		Analyzer analyzer = searchIndexReader.getQueryAnalyzerFromPool();

		IndexSetting indexSetting = searchIndexReader.indexSetting();
		if (highlightInfo != null) {
			String queryAnalyzerName = indexSetting.getQueryAnalyzer();
			for (RefSetting refSetting : indexSetting.getFieldList()) {
				highlightInfo.add(refSetting.getRef(), queryAnalyzerName, term.termString(), term.option().useHighlight());
			}
		}
		try {
			CharTermAttribute termAttribute = null;
			CharsRefTermAttribute refTermAttribute = null;
			PositionIncrementAttribute positionAttribute = null;
			StopwordAttribute stopwordAttribute = null;
			TypeAttribute typeAttribute = null;
			AdditionalTermAttribute additionalTermAttribute = null;
			
			SynonymAttribute synonymAttribute = null;
//			FeatureAttribute featureAttribute = null;
			
			//검색옵션에 따라 analyzerOption도 수정.
			AnalyzerOption analyzerOption = new AnalyzerOption();
			analyzerOption.useStopword(searchOption.useStopword());
			analyzerOption.useSynonym(searchOption.useSynonym());
			
			TokenStream tokenStream = analyzer.tokenStream(indexId, fullTerm.getReader(), analyzerOption);
			tokenStream.reset();

			CharTermAttribute charTermAttribute = tokenStream.getAttribute(CharTermAttribute.class);
			
			if (tokenStream.hasAttribute(CharsRefTermAttribute.class)) {
				refTermAttribute = tokenStream.getAttribute(CharsRefTermAttribute.class);
			}
			if (tokenStream.hasAttribute(CharTermAttribute.class)) {
				termAttribute = tokenStream.getAttribute(CharTermAttribute.class);
			}
			if (tokenStream.hasAttribute(PositionIncrementAttribute.class)) {
				positionAttribute = tokenStream.getAttribute(PositionIncrementAttribute.class);
			}
			if (tokenStream.hasAttribute(StopwordAttribute.class)) {
				stopwordAttribute = tokenStream.getAttribute(StopwordAttribute.class);
			}
			if (tokenStream.hasAttribute(TypeAttribute.class)) {
				typeAttribute = tokenStream.getAttribute(TypeAttribute.class);
			}
			if (tokenStream.hasAttribute(AdditionalTermAttribute.class)) {
				additionalTermAttribute = tokenStream.getAttribute(AdditionalTermAttribute.class);
			}
			if (tokenStream.hasAttribute(SynonymAttribute.class)) {
				synonymAttribute = tokenStream.getAttribute(SynonymAttribute.class);
			}
//			if (tokenStream.hasAttribute(FeatureAttribute.class)) {
//				featureAttribute = tokenStream.getAttribute(FeatureAttribute.class);
//			}

			CharVector token = null;
			while (tokenStream.incrementToken()) {

				//요청 타입이 존재할때 타입이 다르면 단어무시.
				if(requestTypeAttribute != null && typeAttribute != null){
					if(requestTypeAttribute != typeAttribute.type()){
						continue;
					}
				}
				/* 
				 * stopword 
				 * */
				if (stopwordAttribute != null && stopwordAttribute.isStopword()) {
//					logger.debug("stopword");
					continue;
				}

				/*
				 * Main 단어는 tf를 적용하고, 나머지는 tf를 적용하지 않는다.
				 * */
				if (refTermAttribute != null) {
					CharsRef charRef = refTermAttribute.charsRef();

					if (charRef != null) {
						char[] buffer = new char[charRef.length()];
						System.arraycopy(charRef.chars, charRef.offset, buffer, 0, charRef.length);
						token = new CharVector(buffer, 0, buffer.length, indexSetting.isIgnoreCase());
					} else if (termAttribute != null && termAttribute.buffer() != null) {
						token = new CharVector(termAttribute.buffer(), indexSetting.isIgnoreCase());
					}
				} else {
					token = new CharVector(charTermAttribute.buffer(), 0, charTermAttribute.length(), indexSetting.isIgnoreCase());
				}
				
				logger.debug("token > {}, isIgnoreCase = {}", token, token.isIgnoreCase());
				int queryPosition = positionAttribute != null ? positionAttribute.getPositionIncrement() : 0;
//				logger.debug("token = {} : {}", token, queryPosition);

				SearchMethod searchMethod = searchIndexReader.createSearchMethod(new NormalSearchMethod());
				PostingReader postingReader = searchMethod.search(indexId, token, queryPosition, weight);

				OperatedClause clause = new TermOperatedClause(indexId, postingReader);
				
				// 유사어 처리
				// analyzerOption에 synonym확장여부가 들어가 있으므로, 여기서는 option을 확인하지 않고,
				// 있으면 그대로 색인하고 유사어가 없으면 색인되지 않는다.
				//
				if(synonymAttribute != null) {
					CharVector[] synonym = synonymAttribute.getSynonym();
					if(synonym != null) {
						OperatedClause synonymClause = null;
						for(CharVector localToken : synonym) {
							localToken.setIgnoreCase();
							SearchMethod localSearchMethod = searchIndexReader.createSearchMethod(new NormalSearchMethod());
							PostingReader localPostingReader = localSearchMethod.search(indexId, localToken, queryPosition, weight);
							OperatedClause localClause = new TermOperatedClause(indexId, localPostingReader);
							
							if(synonymClause == null) {
								synonymClause = localClause;
							} else {
								synonymClause = new OrOperatedClause(synonymClause, localClause);
							}
						}
						
						if(synonymClause != null) {
							clause = new OrOperatedClause(clause, synonymClause);
						}
					}
				}
				
//				if (featureAttribute != null && featureAttribute.type() == FeatureType.APPEND) {
//					//TODO append 한다.
//					clause = new TermOperatedClause(postingReader);
//					if(operatedClause != null){
//						clause = new AppendOperatedClause(operatedClause, clause);
//					}
//				}else{
//					clause = new TermOperatedClause(indexId, postingReader);
//				}
				
				if (operatedClause == null) {
					operatedClause = clause;
				} else {
					if(term.type() == Type.ALL){
						operatedClause = new AndOperatedClause(operatedClause, clause);
					}else if(term.type() == Type.ANY){
						operatedClause = new OrOperatedClause(operatedClause, clause);
					}
				}
			}

			//추가 확장 단어들.
			if(additionalTermAttribute != null) {
				Iterator<String[]> iterator = additionalTermAttribute.iterateAdditionalTerms();
				OperatedClause additionalClause = null;
				
				if(iterator != null) {
					while(iterator.hasNext()) {
						String[] str = iterator.next();
						
						CharVector localToken = new CharVector(str[0].toCharArray(), indexSetting.isIgnoreCase());
						
						int queryPosition = positionAttribute != null ? positionAttribute.getPositionIncrement() : 0;
						SearchMethod searchMethod = searchIndexReader.createSearchMethod(new NormalSearchMethod());
						PostingReader postingReader = searchMethod.search(indexId, localToken, queryPosition, weight);
						OperatedClause clause = new TermOperatedClause(indexId, postingReader);
						
						if(additionalClause == null) {
							additionalClause = clause;
						} else {
							additionalClause = new OrOperatedClause(additionalClause, clause);
						}
					}
					
					if(additionalClause != null) {
						operatedClause = new OrOperatedClause(operatedClause, additionalClause);
					}
				}
			}
			
		} catch (IOException e) {
			logger.error("", e);
		} finally {
			searchIndexReader.releaseQueryAnalyzerToPool(analyzer);
		}
	}

	@Override
	protected boolean nextDoc(RankInfo rankInfo) {
		if (operatedClause == null) {
			return false;
		}
		return operatedClause.next(rankInfo);
	}

	@Override
	public void close() {
		if (operatedClause != null) {
			operatedClause.close();
		}
	}
	@Override
	protected void initClause(boolean explain) {
		if (operatedClause != null) {
			operatedClause.init(explanation != null ? explanation.createSubExplanation() : null);
		}
	}
	
//	@Override
//	protected void initExplanation() {
//		if(operatedClause != null){
//			operatedClause.setExplanation(explanation.createSub1());
//		}
//		explanation.setTerm(termString);
//	}
	
	@Override
	public String term() {
		return termString;
	}

}
