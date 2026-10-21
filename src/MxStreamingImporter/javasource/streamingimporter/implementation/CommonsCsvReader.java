package streamingimporter.implementation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import streamingimporter.implementation.XssfExcelReader.RowProcessor;

public class CommonsCsvReader {

	/**
	 * CSV ファイルをストリーミングで読み込み、RowProcessor に渡す
	 */
	public static void read(
			InputStream csvIs,
			Charset charset,
			String separatorChar,
			String quoteChar,
			String escapeChar,
			RowProcessor rowProcessor) throws Exception {

		Charset encoding = (charset != null) ? charset : StandardCharsets.UTF_8;

		// CSVFormat のビルダー構築
		CSVFormat.Builder builder = CSVFormat.DEFAULT.builder()
				.setIgnoreEmptyLines(true)
				.setTrim(true);

		// 1. Separator (区切り文字: 未指定ならカンマ ',')
		if (separatorChar != null && !separatorChar.isEmpty()) {
			builder.setDelimiter(separatorChar.charAt(0));
		}

		// 2. Quote (囲み文字: 未指定ならダブルクォーテーション '"')
		if (quoteChar != null && !quoteChar.isEmpty()) {
			builder.setQuote(quoteChar.charAt(0));
		}

		// 3. Escape (エスケープ文字)
		if (escapeChar != null && !escapeChar.isEmpty()) {
			builder.setEscape(escapeChar.charAt(0));
		}

		CSVFormat format = builder.get();

		try (InputStreamReader isr = new InputStreamReader(csvIs, encoding);
				CSVParser parser = CSVParser.parse(isr, format)) {

			int rowIndex = 0;
			for (CSVRecord record : parser) {
				rowIndex++; // 1ベースの行番号

				Map<String, String> rowData = new HashMap<>();
				// カラムインデックス (0, 1, 2...) を Excel形式の列記号 ("A", "B", "C"...) に変換して格納
				for (int i = 0; i < record.size(); i++) {
					String colKey = idxToExcelColumn(i);
					String value = record.get(i);
					if (value == null || value.trim().isEmpty()) {
						continue; // 空文字列はスキップ
					}
					rowData.put(colKey, value);
				}

				// RowProcessor 呼び出し
				if (!rowData.isEmpty()) {
					rowProcessor.processRow(rowIndex, rowData);
				}
			}
		}
	}

	private static String idxToExcelColumn(int index) {
		StringBuilder sb = new StringBuilder();
		while (index >= 0) {
			sb.insert(0, (char) ('A' + (index % 26)));
			index = (index / 26) - 1;
		}
		return sb.toString();
	}
}
