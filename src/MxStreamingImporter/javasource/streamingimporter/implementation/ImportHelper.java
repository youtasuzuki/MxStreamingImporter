package streamingimporter.implementation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive.PrimitiveType;

import streamingimporter.implementation.XssfExcelReader.RowProcessor;
import streamingimporter.proxies.constants.Constants;

public class ImportHelper {

	public static final ILogNode logger = Core.getLogger("StreamingImporter");
	// IContext.getData() 用のキー
	private static final String CONTEXT_KEY_SKIPPED_LINES = "StreamingImporter.SkippedLines";


	/**
	 * 0ベースインデックスを Excel列記号に変換 (0 -> "A", 1 -> "B", 25 -> "Z", 26 -> "AA")
	 */
	public static String idxToExcelColumn(int index) {
		StringBuilder sb = new StringBuilder();
		while (index >= 0) {
			sb.insert(0, (char) ('A' + (index % 26)));
			index = (index / 26) - 1;
		}
		return sb.toString();
	}

	/**
	 * スキップされた行の指定セル値を取得する (1-based lineNumber, 1-based columnNumber)
	 */
	@SuppressWarnings("unchecked")
	public static String getSkippedLineValue(IContext context, long lineNumber, long columnNumber) {
		if (context == null || lineNumber <= 0 || columnNumber <= 0) {
			return null;
		}
		Object data = context.getData().get(CONTEXT_KEY_SKIPPED_LINES);
		if (!(data instanceof Map)) {
			return null;
		}
		Map<Long, Map<String, String>> lines = (Map<Long, Map<String, String>>) data;
		Map<String, String> row = lines.get(lineNumber);
		if (row == null) {
			return null;
		}
		String colKey = idxToExcelColumn((int) (columnNumber - 1));
		return row.get(colKey);
	}

	/**
	 * FileDocumentへのアソシエーション（またはそのサブクラス）を自動検索
	 */
	public static String findFileDocumentAssociation(IMetaObject targetMeta) {
		for (var assoc : targetMeta.getMetaAssociationsParent()) {
			IMetaObject child = assoc.getChild();
			if (child != null && isFileDocumentOrSubclass(child)) {
				return assoc.getName();
			}
		}
		return null;
	}

	private static boolean isFileDocumentOrSubclass(IMetaObject meta) {
		if ("System.FileDocument".equals(meta.getName()))
			return true;
		IMetaObject superMeta = meta.getSuperObject();
		return superMeta != null && isFileDocumentOrSubclass(superMeta);
	}

	/**
	 * 共通の行処理プロセッサ (RowProcessor の実装)
	 */
	public static class ImportRowProcessor implements RowProcessor {
		private final IContext context;
		private final IMetaObject targetMeta;
		private final IMendixObject mappingObj;
		private final IMendixObject fileDocObj;
		private final String fileDocAssocName;
		private final long headerLineNumber;
		private final long skipCount;
		private final long batchSize;
		private final long txChunkSize;
		private final String callbackMicroflow;
		private final List<IMendixObject> importedObjectsOutput;

		private final Map<String, String> headerColumnMap = new HashMap<>(); // colKey -> HeaderName
		private final Map<String, String> attrToColMap = new HashMap<>(); // attrName -> colKey
		private final List<IMendixObject> currentBatch = new ArrayList<>();

		private final Map<Long, Map<String, String>> skippedLinesMap = new HashMap<>();

		private long processedCount = 0;
		private long txProcessedCount = 0;
		private final String FILL_LINE_NUMBER = Constants.getFILL_LINE_NUMBER();

		public ImportRowProcessor(IContext originalContext, IContext workingContext, IMetaObject targetMeta,
				IMendixObject mappingObj,
				IMendixObject fileDocObj, String fileDocAssocName,
				long headerLineNumber, long skipCount, long batchSize,
				long txChunkSize, String callbackMicroflow,
				List<IMendixObject> importedObjectsOutput) {
			this.context = workingContext;
			this.targetMeta = targetMeta;
			this.mappingObj = mappingObj;
			this.fileDocObj = fileDocObj;
			this.fileDocAssocName = fileDocAssocName;
			this.headerLineNumber = headerLineNumber;
			this.skipCount = skipCount;
			this.batchSize = batchSize;
			this.txChunkSize = txChunkSize;
			this.callbackMicroflow = callbackMicroflow;
			this.importedObjectsOutput = importedObjectsOutput;
			// 呼び出し元のコンテキストと実行用コンテキストの両方にスキップ行データ保持要の空のマップを登録
			if (originalContext != null) {
				originalContext.getData().put(CONTEXT_KEY_SKIPPED_LINES, this.skippedLinesMap);
			}
			if (workingContext != null) {
				workingContext.getData().put(CONTEXT_KEY_SKIPPED_LINES, this.skippedLinesMap);
			}
		}

		@Override
		public void processRow(int rowIndex, Map<String, String> rowData) throws Exception {
			long currentLine = rowIndex;

			// 1. ヘッダー行の解析
			if (currentLine == this.headerLineNumber) {
				// ヘッダー名に含まれる BOM (\uFEFF) を最初に一括除去して保持する
				for (Map.Entry<String, String> entry : rowData.entrySet()) {
					String rawHeader = entry.getValue();
					String cleanHeader = (rawHeader != null && rawHeader.startsWith("\uFEFF"))
							? rawHeader.substring(1)
							: rawHeader;
					this.headerColumnMap.put(entry.getKey(), cleanHeader);
				}
				//this.headerColumnMap.putAll(rowData);
				buildAttributeMapping();
				return;
			}

			// スキップ対象行であればマップに保存して終了
			if (currentLine <= this.skipCount) {
				Map<String, String> copiedRow = new HashMap<>();
				for (Map.Entry<String, String> entry : rowData.entrySet()) {
					String val = entry.getValue();
					if (currentLine == 1 && val != null && val.startsWith("\uFEFF")) {
						val = val.substring(1);
					}
					copiedRow.put(entry.getKey(), val);
				}
				this.skippedLinesMap.put(currentLine, copiedRow);
				return;
			}

			// 2. データ行の処理
			IMendixObject newObj = Core.instantiate(context, targetMeta.getName());

			// 列の値をEntityの属性にセット
			for (Map.Entry<String, String> entry : attrToColMap.entrySet()) {
				String attrName = entry.getKey();
				String colKey = entry.getValue();

				if (FILL_LINE_NUMBER.equals(colKey)) {
					// 行番号を6桁ゼロ埋めしてセット (1ベース)
					newObj.setValue(context, attrName, String.format("%06d", currentLine));
				} else {
					String cellValue = rowData.get(colKey);
					if (cellValue != null) {
						newObj.setValue(context, attrName, cellValue);
					}
				}
			}

			// FileDocument アソシエーションの自動設定
			if (fileDocAssocName != null && fileDocObj != null) {
				newObj.setValue(context, fileDocAssocName, fileDocObj.getId());
			}

			currentBatch.add(newObj);
			if (importedObjectsOutput != null) {
				importedObjectsOutput.add(newObj);
			}

			processedCount++;
			txProcessedCount++;

			// バッチサイズに達したらコミット処理
			if (currentBatch.size() >= batchSize) {
				flushBatch();
			}

			// トランザクション分割（指定がある場合）
			if (txChunkSize > 0 && txProcessedCount >= txChunkSize) {
				if (context.isInTransaction()) {
					context.endTransaction();
					context.startTransaction();
					if (logger.isDebugEnabled()) {
						logger.debug("Transaction chunk committed after processing " + txProcessedCount + " rows.");
					}
				}
				txProcessedCount = 0;
			}
		}

		/**
		 * マッピング定義の構築 (属性名 <-> 列キー)
		 */
		private void buildAttributeMapping() {
			attrToColMap.clear();

			// 対象エンティティの String 型属性のみを取得
			List<IMetaPrimitive> primitives = new ArrayList<>();
			for (IMetaPrimitive p : targetMeta.getMetaPrimitives()) {
				if (!p.isVirtual() && p.getType() == PrimitiveType.String) {
					primitives.add(p);
				}
			}

			if (mappingObj != null) {
				// パターンA: columnNameMapping が指定されている場合
				for (IMetaPrimitive p : primitives) {
					String attrName = p.getName();
					String mappedValue = mappingObj.getValue(context, attrName);
					String targetSearch = null;

					if (mappedValue != null && !mappedValue.trim().isEmpty()) {
						// 1. 明示的に値が設定されている場合 ($A, $1, $LINE_NUMBER, 任意ヘッダー名 等)
						targetSearch = mappedValue.trim();
					} else {
						// 2. 属性の値が empty（未設定）の場合 -> 属性名そのものを検索キーにする
						targetSearch = attrName;
					}

					String colKey = parseColumnKeyOrHeader(targetSearch, attrName);
					if (colKey != null) {
						attrToColMap.put(attrName, colKey);
					}
				}
			} else {
				// パターンB: columnNameMapping が empty の場合（出現順に割り当て）
				List<String> sortedColKeys = new ArrayList<>(headerColumnMap.keySet());
				sortedColKeys.sort(Comparator.comparingInt(this::excelColumnToIdx));

				for (int i = 0; i < primitives.size() && i < sortedColKeys.size(); i++) {
					attrToColMap.put(primitives.get(i).getName(), sortedColKeys.get(i));
				}
			}
		}

		private String parseColumnKeyOrHeader(String targetStr, String attrName) {
			if (targetStr == null)
				return null;
			String clean = targetStr.trim();

			if (Constants.getFILL_LINE_NUMBER().equalsIgnoreCase(clean)) {
				return Constants.getFILL_LINE_NUMBER();
			}

			if ("=".equals(clean)) {
				clean = attrName;
			}

			if (clean.startsWith("$")) {
				String sub = clean.substring(1).trim();
				if (sub.matches("(?i)^[A-Z]+$"))
					return sub.toUpperCase();
				if (sub.matches("^\\d+$"))
					return idxToExcelColumn(Integer.parseInt(sub) - 1);
			}

			for (Map.Entry<String, String> entry : headerColumnMap.entrySet()) {
				if (clean.equalsIgnoreCase(entry.getValue())) {
					return entry.getKey();
				}
			}

			if (clean.matches("(?i)^[A-Z]+$")) {
				return clean.toUpperCase();
			}

			if (clean.matches("^\\d+$")) {
				int oneBasedIdx = Integer.parseInt(clean);
				if (oneBasedIdx > 0) {
					return idxToExcelColumn(oneBasedIdx - 1);
				}
			}

			return null;
		}

		private int excelColumnToIdx(String col) {
			int result = 0;
			for (int i = 0; i < col.length(); i++) {
				result = result * 26 + (col.charAt(i) - 'A' + 1);
			}
			return result - 1;
		}

		public void flushBatch() throws Exception {
			if (currentBatch.isEmpty())
				return;

			if (callbackMicroflow != null && !callbackMicroflow.contains("CB_empty")
					&& !callbackMicroflow.trim().isEmpty()) {
				try {
					Core.microflowCall(callbackMicroflow)
							.withParam("DestinationList", new ArrayList<>(currentBatch))
							.withParam("XlsxFileDocument", fileDocObj)
							.execute(context);
				} catch (Exception e) {
					throw new RuntimeException("Error executing callback microflow: " + callbackMicroflow, e);
				}
			}

			if (targetMeta.isPersistable()) {
				Core.commit(context, currentBatch);
				if (logger.isDebugEnabled()) {
					logger.debug("Committed batch of " + currentBatch.size() + " objects to entity: "
							+ targetMeta.getName());
				}
			}

			currentBatch.clear();
		}

		public long getProcessedCount() {
			return processedCount;
		}
	}
}