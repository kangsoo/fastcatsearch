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

package org.fastcatsearch.ir.search.clause;

import java.io.PrintStream;

import org.fastcatsearch.ir.query.RankInfo;


/**
 * 조건없이 모든 문서를 리턴한다.
 * @author sangwook.song
 *
 */
public class AllDocumentOperatedClause extends OperatedClause {
	private int docCount;
	private int pos;
	
	public AllDocumentOperatedClause(int docCount){
		super("ALL");
		this.docCount = docCount;
	}

	protected boolean nextDoc(RankInfo rankInfo) {
		if(pos < docCount){
			if(isExplain()){
				rankInfo.explain(id, 0, "");
			}
			rankInfo.init(pos, 0);
			pos++;
			return true;
		}
		rankInfo.init(-1,-1);
		return false;
	}

	@Override
	public void close() {
		
	}

	@Override
	protected void initClause(boolean explain) {
	}

//	@Override
//	public void initExplanation() {
//	}

	@Override
	public void printTrace(PrintStream os, int depth) {
		int indentSize = 4;
		String indent = "";
		if(depth > 0){
			for (int i = 0; i < (depth - 1) * indentSize; i++) {
				indent += " ";
			}
			
			for (int i = (depth - 1) * indentSize, p = 0; i < depth * indentSize; i++, p++) {
				if(p == 0){
					indent += "|";
				}else{
					indent += "-";
				}
			}
		}
		os.println(indent+"[ALL] count["+docCount+"]");
	}
}
