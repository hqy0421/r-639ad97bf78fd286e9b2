package technology.tabula.extractors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Arrays;

import technology.tabula.Line;
import technology.tabula.Page;
import technology.tabula.Rectangle;
import technology.tabula.Ruling;
import technology.tabula.Table;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;

public class BasicExtractionAlgorithm implements ExtractionAlgorithm {

    private List<Ruling> verticalRulings = null;

    private boolean splitSideBySideTables = false;
    private float splitMinGapWidth = SideBySideTableSplitter.DEFAULT_MIN_GAP_WIDTH;
    private int splitMinValidRows = SideBySideTableSplitter.DEFAULT_MIN_VALID_ROWS;
    private int splitMinBlocksPerRow = SideBySideTableSplitter.DEFAULT_MIN_BLOCKS_PER_ROW;

    public BasicExtractionAlgorithm() {
    }

    public BasicExtractionAlgorithm(List<Ruling> verticalRulings) {
        this.verticalRulings = verticalRulings;
    }

    /**
     * Enables splitting candidate areas that contain several borderless tables
     * printed side by side. When disabled (the default), extraction behavior is
     * unchanged.
     */
    public void setSplitSideBySideTables(boolean splitSideBySideTables) {
        this.splitSideBySideTables = splitSideBySideTables;
    }

    public boolean isSplitSideBySideTables() {
        return splitSideBySideTables;
    }

    public void setSplitMinGapWidth(float splitMinGapWidth) {
        this.splitMinGapWidth = splitMinGapWidth;
    }

    public void setSplitMinValidRows(int splitMinValidRows) {
        this.splitMinValidRows = splitMinValidRows;
    }

    public void setSplitMinBlocksPerRow(int splitMinBlocksPerRow) {
        this.splitMinBlocksPerRow = splitMinBlocksPerRow;
    }
    
    public List<Table> extract(Page page, List<Float> verticalRulingPositions) {
        List<Ruling> verticalRulings = new ArrayList<>(verticalRulingPositions.size());
        for (Float p: verticalRulingPositions) {
            verticalRulings.add(new Ruling(page.getTop(), p, 0.0f, (float) page.getHeight()));
        }
        this.verticalRulings = verticalRulings;
        return this.extract(page);
    }

    @Override
    public List<Table> extract(Page page) {

        List<TextElement> textElements = page.getText();

        if (textElements.size() == 0) {
            return Arrays.asList(new Table[] { Table.empty() });
        }

        // Optional: split side-by-side borderless tables before extraction. Only
        // the automatic column detection path is affected; explicit vertical
        // rulings or a disabled flag keep the original single-table behavior.
        if (this.splitSideBySideTables && this.verticalRulings == null) {
            SideBySideTableSplitter splitter = new SideBySideTableSplitter(
                    this.splitMinGapWidth, this.splitMinValidRows, this.splitMinBlocksPerRow);
            List<Page> subPages = splitter.split(page);

            if (subPages.size() > 1) {
                List<Table> tables = new ArrayList<>(subPages.size());
                for (Page subPage : subPages) {
                    // Sub-areas are already split; extract each with the
                    // original logic, ordered left to right.
                    tables.add(extractSingleTable(subPage));
                }
                return tables;
            }
        }

        return Arrays.asList(new Table[] { extractSingleTable(page) });
    }

    private Table extractSingleTable(Page page) {

        List<TextChunk> textChunks = this.verticalRulings == null ? TextElement.mergeWords(page.getText()) : TextElement.mergeWords(page.getText(), this.verticalRulings);
        List<Line> lines = TextChunk.groupByLines(textChunks);
        List<Float> columns = null;

        if (this.verticalRulings != null) {
            Collections.sort(this.verticalRulings, new Comparator<Ruling>() {
                @Override
                public int compare(Ruling arg0, Ruling arg1) {
                    return Double.compare(arg0.getLeft(), arg1.getLeft());
                }
            });
            columns = new ArrayList<>(this.verticalRulings.size());
            for (Ruling vr: this.verticalRulings) {
                columns.add(vr.getLeft());
            }
        }
        else {
            columns = columnPositions(lines);
        }

        Table table = new Table(this);
        table.setRect(page.getLeft(), page.getTop(), page.getWidth(), page.getHeight());
        table.setPageNumber(page.getPageNumber());

        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            List<TextChunk> elements = line.getTextElements();

            Collections.sort(elements, new Comparator<TextChunk>() {

				@Override
				public int compare(TextChunk o1, TextChunk o2) {
					return Float.compare(o1.getLeft(), o2.getLeft());
				}
			});

            for (TextChunk tc: elements) {
                if (tc.isSameChar(Line.WHITE_SPACE_CHARS)) {
                    continue;
                }

                int j = 0;
                boolean found = false;
                for(; j < columns.size(); j++) {
                    if (tc.getLeft() <= columns.get(j)) {
                        found = true;
                        break;
                    }
                }
                table.add(tc, i, found ? j : columns.size());
            }
        }

        return table;
    }
    
    @Override
    public String toString() {
        return "stream";
    }
    
    
    /**
     * @param lines must be an array of lines sorted by their +top+ attribute
     * @return a list of column boundaries (x axis)
     */
    public static List<java.lang.Float> columnPositions(List<Line> lines) {

        List<Rectangle> regions = new ArrayList<>();
        for (TextChunk tc: lines.get(0).getTextElements()) {
            if (tc.isSameChar(Line.WHITE_SPACE_CHARS)) { 
                continue; 
            }
            Rectangle r = new Rectangle();
            r.setRect(tc);
            regions.add(r);
        }
        
        for (Line l: lines.subList(1, lines.size())) {
            List<TextChunk> lineTextElements = new ArrayList<>();
            for (TextChunk tc: l.getTextElements()) {
                if (!tc.isSameChar(Line.WHITE_SPACE_CHARS)) { 
                    lineTextElements.add(tc);
                }
            }
            
            for (Rectangle cr: regions) {

                List<TextChunk> overlaps = new ArrayList<>();
                for (TextChunk te: lineTextElements) {
                    if (cr.horizontallyOverlaps(te)) {
                        overlaps.add(te);
                    }
                }
                
                for (TextChunk te: overlaps) {
                    cr.merge(te);
                }
                
                lineTextElements.removeAll(overlaps);
            }
            
            for (TextChunk te: lineTextElements) {
                Rectangle r = new Rectangle();
                r.setRect(te);
                regions.add(r);
            }
        }
        
        List<java.lang.Float> rv = new ArrayList<>();
        for (Rectangle r: regions) {
            rv.add(r.getRight());
        }
        
        Collections.sort(rv);
        
        return rv;
        
    }

}
