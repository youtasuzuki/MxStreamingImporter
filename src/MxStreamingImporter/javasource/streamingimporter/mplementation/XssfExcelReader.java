package streamingimporter.mplementation;

import java.io.File;
import java.io.InputStream;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.XMLReader;

public class XssfExcelReader {

	@FunctionalInterface
	public interface SheetFilter {
        boolean test(String sheetName) throws Exception;
    }

	@FunctionalInterface
	public interface RowProcessor {
		void processRow(int rowIndex, Map<String, String> rowData) throws Exception;;
	}

	public static void read(InputStream xlsxIs, String targetSheet, RowProcessor rowProcessor) throws Exception {
		try (OPCPackage pkg = OPCPackage.open(xlsxIs)) {
			ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
			XSSFReader reader = new XSSFReader(pkg);
			StylesTable styles = reader.getStylesTable();
			XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
			while (sheets.hasNext()) {
				try (InputStream is = sheets.next()) {
					String sheetName = sheets.getSheetName();
					if (targetSheet != null && !targetSheet.equals(sheetName)) {
	                    continue;
	                }
					DataFormatter formatter = new DataFormatter();
					SheetContentsHandler handler = new InternalSheetHandler(rowProcessor);
					XSSFSheetXMLHandler sheetHandler = new XSSFSheetXMLHandler(styles, null, strings, handler, formatter, false);
					XMLReader parser = XMLHelper.newXMLReader();
					parser.setContentHandler(sheetHandler);
					parser.parse(new org.xml.sax.InputSource(is));
					break; // Exit after processing the target sheet
				}
			}
		}
	}

	public static void readAllSheets(File excelFile, SheetFilter sheetFilter, RowProcessor rowProcessor) throws Exception {
		try (OPCPackage pkg = OPCPackage.open(excelFile)) {
			ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
			XSSFReader reader = new XSSFReader(pkg);
			StylesTable styles = reader.getStylesTable();
			XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
			while (sheets.hasNext()) {
				try (InputStream is = sheets.next()) {
					String sheetName = sheets.getSheetName();
					if (sheetFilter != null && !sheetFilter.test(sheetName)) {
	                    continue;
	                }
					DataFormatter formatter = new DataFormatter();
					SheetContentsHandler handler = new InternalSheetHandler(rowProcessor);
					XSSFSheetXMLHandler sheetHandler = new XSSFSheetXMLHandler(styles, null, strings, handler, formatter, false);
					XMLReader parser = XMLHelper.newXMLReader();
					parser.setContentHandler(sheetHandler);
					parser.parse(new org.xml.sax.InputSource(is));
				}
			}
		}
	}
	
	private static class InternalSheetHandler implements SheetContentsHandler {
		private final RowProcessor rowProcessor;
		private int currentRowIndex;
		Map<String, String> rowData;
		public InternalSheetHandler(RowProcessor rowProcessor) {
            this.rowProcessor = rowProcessor;
        }		
		@Override
		public void startRow(int rowNum) {
			currentRowIndex = rowNum + 1;
			rowData = new java.util.HashMap<>();
		}
		@Override
		public void cell(String cellReference, String formattedValue, XSSFComment comment) {
			String columnName = cellReference.replaceAll("\\d", "");
			rowData.put(columnName, formattedValue);
		}
		@Override
		public void endRow(int rowNum) {
			try {
				rowProcessor.processRow(currentRowIndex, rowData);
			} catch (Exception e) {
				throw new RuntimeException("Error processing row " + currentRowIndex, e);
			}
		}
	}
}