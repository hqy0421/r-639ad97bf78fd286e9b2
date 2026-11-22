package technology.tabula;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SideBySideTableSplitter;

public class TestSideBySideSplit {

    private static final String MULTICOLUMN_PDF = "src/test/resources/technology/tabula/MultiColumn.pdf";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    // Synthetic PDF generation helpers
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    /**
     * Writes a page containing {@code tables.length} borderless tables side by
     * side. Each table has {@code blocksPerRow} columns and {@code rows} rows.
     * The gap between two neighboring tables is taken from {@code gapWidths}
     * (one entry per gap).
     */
    private File writeSideBySidePdf(int tables, int blocksPerRow, int rows, float... gapWidths) throws IOException {
        final float pageWidth = 900f;
        final float pageHeight = 500f;
        final float fontSize = 10f;
        final float columnPitch = 60f;
        final float rowPitch = 14f;
        final float firstX = 50f;
        final float firstBaselineFromTop = 40f;

        PDDocument doc = new PDDocument();
        PDPage pdPage = new PDPage(new PDRectangle(pageWidth, pageHeight));
        doc.addPage(pdPage);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

        // all labels have the same length, so every block has the same measured width
        float labelWidth = font.getStringWidth("T0R0C0") / 1000f * fontSize;

        float[] tableStartX = new float[tables];
        tableStartX[0] = firstX;
        for (int t = 1; t < tables; t++) {
            float previousTableWidth = (blocksPerRow - 1) * columnPitch + labelWidth;
            tableStartX[t] = tableStartX[t - 1] + previousTableWidth + gapWidths[t - 1];
        }

        try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
            cs.beginText();
            cs.setFont(font, fontSize);
            for (int r = 0; r < rows; r++) {
                float baselineY = pageHeight - firstBaselineFromTop - r * rowPitch;
                for (int t = 0; t < tables; t++) {
                    for (int c = 0; c < blocksPerRow; c++) {
                        float x = tableStartX[t] + c * columnPitch;
                        cs.setTextMatrix(new Matrix(1, 0, 0, 1, x, baselineY));
                        cs.showText(String.format("T%dR%dC%d", t, r, c));
                    }
                }
            }
            cs.endText();
        }

        File pdf = folder.newFile("side-by-side.pdf");
        doc.save(pdf);
        doc.close();
        return pdf;
    }

    private Page firstPage(File pdf) throws IOException {
        PDDocument document = Loader.loadPDF(pdf);
        ObjectExtractor oe = new ObjectExtractor(document);
        return oe.extract(1);
    }

    private static BasicExtractionAlgorithm splittingExtractor(float minGapWidth, int minValidRows, int minBlocksPerRow) {
        BasicExtractionAlgorithm bea = new BasicExtractionAlgorithm();
        bea.setSplitSideBySideTables(true);
        bea.setSplitMinGapWidth(minGapWidth);
        bea.setSplitMinValidRows(minValidRows);
        bea.setSplitMinBlocksPerRow(minBlocksPerRow);
        return bea;
    }

    private static BasicExtractionAlgorithm splittingExtractor() {
        return splittingExtractor(
                SideBySideTableSplitter.DEFAULT_MIN_GAP_WIDTH,
                SideBySideTableSplitter.DEFAULT_MIN_VALID_ROWS,
                SideBySideTableSplitter.DEFAULT_MIN_BLOCKS_PER_ROW);
    }

    private static List<String> allCellText(Table table) {
        List<String> cells = new ArrayList<>();
        for (List<RectangularTextContainer> row : table.getRows()) {
            for (RectangularTextContainer cell : row) {
                cells.add(cell.getText());
            }
        }
        return cells;
    }

    private static List<String> allCellText(List<Table> tables) {
        List<String> cells = new ArrayList<>();
        for (Table table : tables) {
            cells.addAll(allCellText(table));
        }
        return cells;
    }

    private static char[] sortedChars(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (String cell : cells) {
            sb.append(cell);
        }
        char[] chars = sb.toString().toCharArray();
        Arrays.sort(chars);
        return chars;
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    // Splitter behavior
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    @Test
    public void testRecursivelySplitsThreeSideBySideTables() throws IOException {
        // gaps of 77.5 and 107.5 points: the widest one must be cut first, then
        // the remaining two-table area is cut with the same rule.
        File pdf = writeSideBySidePdf(3, 2, 5, 77.5f, 107.5f);
        Page page = firstPage(pdf);

        List<Page> areas = new SideBySideTableSplitter().split(page);
        assertEquals(3, areas.size());
        // results stay ordered left to right
        for (int i = 1; i < areas.size(); i++) {
            assertTrue(areas.get(i - 1).getLeft() < areas.get(i).getLeft());
        }

        List<Table> tables = splittingExtractor().extract(page);
        assertEquals(3, tables.size());
        for (Table table : tables) {
            assertEquals(5, table.getRowCount());
            assertEquals(2, table.getColCount());
        }

        // first table holds the leftmost text, last table the rightmost
        assertEquals("T0R0C0", tables.get(0).getCell(0, 0).getText());
        assertEquals("T2R0C1", tables.get(2).getCell(0, 1).getText());
        page.getPDDoc().close();
    }

    @Test
    public void testSplitLosesOrDuplicatesNoCharacters() throws IOException {
        File pdf = writeSideBySidePdf(3, 2, 5, 77.5f, 107.5f);
        Page page = firstPage(pdf);

        List<Table> unsplit = new BasicExtractionAlgorithm().extract(page);
        List<Table> split = splittingExtractor().extract(page);

        assertEquals(1, unsplit.size());
        assertArrayEquals(sortedChars(allCellText(unsplit)), sortedChars(allCellText(split)));
        page.getPDDoc().close();
    }

    @Test
    public void testDisabledByDefaultProducesSingleWideTable() throws IOException {
        File pdf = writeSideBySidePdf(2, 2, 5, 70f);
        Page page = firstPage(pdf);

        BasicExtractionAlgorithm bea = new BasicExtractionAlgorithm();
        assertFalse(bea.isSplitSideBySideTables());

        List<Table> tables = bea.extract(page);
        assertEquals(1, tables.size());
        assertEquals(4, tables.get(0).getColCount());
        page.getPDDoc().close();
    }

    @Test
    public void testGapBelowWidthThresholdIsNotCut() throws IOException {
        // 12.5pt gap, below the 20pt default threshold: keep the original behavior
        File pdf = writeSideBySidePdf(2, 2, 5, 12.5f);
        Page page = firstPage(pdf);

        assertEquals(1, splittingExtractor().extract(page).size());

        // lowering the threshold makes the same gap eligible
        List<Table> withLowThreshold = splittingExtractor(10f,
                SideBySideTableSplitter.DEFAULT_MIN_VALID_ROWS,
                SideBySideTableSplitter.DEFAULT_MIN_BLOCKS_PER_ROW).extract(page);
        assertEquals(2, withLowThreshold.size());
        page.getPDDoc().close();
    }

    @Test
    public void testFewerRowsThanRequiredIsNotCut() throws IOException {
        // default threshold asks for 3 valid rows per side; only 2 exist
        File pdf = writeSideBySidePdf(2, 2, 2, 70f);
        Page page = firstPage(pdf);

        assertEquals(1, splittingExtractor().extract(page).size());

        // allowing 2 rows per side unlocks the cut
        List<Table> withTwoRows = splittingExtractor(
                SideBySideTableSplitter.DEFAULT_MIN_GAP_WIDTH, 2,
                SideBySideTableSplitter.DEFAULT_MIN_BLOCKS_PER_ROW).extract(page);
        assertEquals(2, withTwoRows.size());
        page.getPDDoc().close();
    }

    @Test
    public void testRowsWithSingleBlockDoNotCountAsValid() throws IOException {
        // each "table" is a single column: a side row has only one block, so the
        // default requirement of two blocks per valid row forbids any cut
        File pdf = writeSideBySidePdf(2, 1, 6, 70f);
        Page page = firstPage(pdf);

        assertEquals(1, splittingExtractor().extract(page).size());

        // accepting single-block rows allows the split
        List<Table> withSingleBlockRows = splittingExtractor(
                SideBySideTableSplitter.DEFAULT_MIN_GAP_WIDTH,
                SideBySideTableSplitter.DEFAULT_MIN_VALID_ROWS, 1).extract(page);
        assertEquals(2, withSingleBlockRows.size());
        page.getPDDoc().close();
    }

    @Test
    public void testWidestGapIsPreferredOverNarrowerGap() throws IOException {
        // the inter-table gap (50) is wider than the intra-table column gap
        // (25); only the inter-table one may be chosen, producing 2 tables of
        // 2 columns rather than cutting inside a table
        File pdf = writeSideBySidePdf(2, 2, 4, 50f);
        Page page = firstPage(pdf);

        // make 25pt column gaps eligible too, so the width-based choice matters
        List<Table> tables = splittingExtractor(20f,
                SideBySideTableSplitter.DEFAULT_MIN_VALID_ROWS,
                SideBySideTableSplitter.DEFAULT_MIN_BLOCKS_PER_ROW).extract(page);

        assertEquals(2, tables.size());
        assertEquals(2, tables.get(0).getColCount());
        assertEquals(2, tables.get(1).getColCount());
        page.getPDDoc().close();
    }

    @Test
    public void testEmptyAreaStillReturnsEmptyTable() throws IOException {
        File pdf = writeSideBySidePdf(2, 2, 5, 70f);
        Page page = firstPage(pdf).getArea(0, 0, 20, 20);

        List<Table> tables = splittingExtractor().extract(page);
        assertEquals(1, tables.size());
        assertEquals(0, tables.get(0).getRowCount());
        page.getPDDoc().close();
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    // Real document: MultiColumn.pdf
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //

    @Test
    public void testMultiColumnPdfSplitsIntoTwoTables() throws IOException {
        Page page = UtilsForTesting.getPage(MULTICOLUMN_PDF, 1);

        List<Table> unsplit = new BasicExtractionAlgorithm().extract(page);
        assertEquals(1, unsplit.size());
        assertEquals(6, unsplit.get(0).getColCount());

        List<Table> split = splittingExtractor().extract(page);
        assertEquals(2, split.size());
        assertEquals(3, split.get(0).getColCount());
        assertEquals(3, split.get(1).getColCount());
        // the left table has 24 text rows; the right table starts at row 5, so
        // its sub-page only contains 20 text rows
        assertEquals(24, split.get(0).getRowCount());
        assertEquals(20, split.get(1).getRowCount());
        assertEquals("1", split.get(0).getCell(0, 0).getText());
        assertEquals("25", split.get(1).getCell(0, 0).getText());

        assertArrayEquals(sortedChars(allCellText(unsplit)), sortedChars(allCellText(split)));
        page.getPDDoc().close();
    }

    @Test
    public void testMultiColumnPdfSplitMatchesAreaBasedExtraction() throws IOException {
        Page wholePage = UtilsForTesting.getPage(MULTICOLUMN_PDF, 1);
        List<Table> split = splittingExtractor().extract(wholePage);

        Page leftArea = UtilsForTesting.getAreaFromPage(MULTICOLUMN_PDF, 1, 0, 0, 451.814f, 212f);
        Page rightArea = UtilsForTesting.getAreaFromPage(MULTICOLUMN_PDF, 1, 0, 212f, 451.814f, 425.169f);
        BasicExtractionAlgorithm bea = new BasicExtractionAlgorithm();

        String[][] expectedLeft = UtilsForTesting.tableToArrayOfRows(bea.extract(leftArea).get(0));
        String[][] expectedRight = UtilsForTesting.tableToArrayOfRows(bea.extract(rightArea).get(0));

        assertArrayEquals(expectedLeft, UtilsForTesting.tableToArrayOfRows(split.get(0)));
        assertArrayEquals(expectedRight, UtilsForTesting.tableToArrayOfRows(split.get(1)));
        wholePage.getPDDoc().close();
    }

    @Test
    public void testExplicitVerticalRulingsIgnoreSplit() throws IOException {
        Page page = UtilsForTesting.getPage(MULTICOLUMN_PDF, 1);
        List<Ruling> rulings = new ArrayList<>();
        for (float x : new float[] {90f, 180f, 270f, 360f}) {
            rulings.add(new Ruling(page.getTop(), x, 0f, (float) page.getHeight()));
        }
        BasicExtractionAlgorithm bea = new BasicExtractionAlgorithm(rulings);
        bea.setSplitSideBySideTables(true);

        List<Table> tables = bea.extract(page);
        assertEquals(1, tables.size());
        page.getPDDoc().close();
    }
}
