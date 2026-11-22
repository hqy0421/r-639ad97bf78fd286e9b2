package technology.tabula.extractors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import technology.tabula.Line;
import technology.tabula.Page;
import technology.tabula.Rectangle;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;

/**
 * Splits a candidate area that actually holds two or more borderless tables
 * printed side by side at the same height. Without an explicit split, stream
 * extraction treats such layouts as a single wide table.
 * <p>
 * The splitter reuses the same text line grouping ({@link TextElement#mergeWords}
 * / {@link TextChunk#groupByLines}) as the extraction algorithm and looks for an
 * internal vertical band that runs through the whole height of the candidate
 * area without any text block crossing it. A cut is only made when:
 * <ul>
 *     <li>the empty band is at least {@code minGapWidth} points wide, and</li>
 *     <li>each side of the band has at least {@code minValidRows} valid rows,
 *         where a valid row contains at least {@code minBlocksPerRow}
 *         non-empty text blocks on that side.</li>
 * </ul>
 * The widest eligible band is chosen; bands of equal width are resolved from
 * left to right. The resulting sub-areas are processed with the same rule
 * until no further cut is possible.
 */
public class SideBySideTableSplitter {

    /** Minimum width, in points, of a vertical empty band that may be cut. */
    public static final float DEFAULT_MIN_GAP_WIDTH = 20f;

    /** Minimum number of valid rows each side of a cut must contain. */
    public static final int DEFAULT_MIN_VALID_ROWS = 3;

    /** Minimum number of non-empty text blocks a valid row must contain on a side. */
    public static final int DEFAULT_MIN_BLOCKS_PER_ROW = 2;

    private final float minGapWidth;
    private final int minValidRows;
    private final int minBlocksPerRow;

    public SideBySideTableSplitter() {
        this(DEFAULT_MIN_GAP_WIDTH, DEFAULT_MIN_VALID_ROWS, DEFAULT_MIN_BLOCKS_PER_ROW);
    }

    public SideBySideTableSplitter(float minGapWidth, int minValidRows, int minBlocksPerRow) {
        if (minGapWidth <= 0) {
            throw new IllegalArgumentException("minGapWidth must be positive");
        }
        if (minValidRows < 1 || minBlocksPerRow < 1) {
            throw new IllegalArgumentException("minValidRows and minBlocksPerRow must be positive");
        }
        this.minGapWidth = minGapWidth;
        this.minValidRows = minValidRows;
        this.minBlocksPerRow = minBlocksPerRow;
    }

    private static final class Cut {
        final float left;
        final float right;
        final float at;

        Cut(float left, float right, float at) {
            this.left = left;
            this.right = right;
            this.at = at;
        }

        float width() {
            return right - left;
        }
    }

    /**
     * Recursively splits the given page area into side-by-side sub-areas.
     * The returned areas are ordered left to right and, when no cut is
     * possible, contain the input area unchanged.
     */
    public List<Page> split(Page page) {
        List<Page> result = new ArrayList<>();
        splitRecursively(page, result);
        return result;
    }

    private void splitRecursively(Page page, List<Page> result) {
        List<Line> lines = buildLines(page);
        Cut cut = findCut(page, lines);

        if (cut == null) {
            result.add(page);
            return;
        }

        // The cut position lies inside a band that no text block reaches, so the
        // two sub-areas partition the text elements without overlap or loss.
        Page leftPage = page.getArea(page.getTop(), page.getLeft(), page.getBottom(), cut.at);
        Page rightPage = page.getArea(page.getTop(), cut.at, page.getBottom(), page.getRight());

        splitRecursively(leftPage, result);
        splitRecursively(rightPage, result);
    }

    private List<Line> buildLines(Page page) {
        List<TextChunk> textChunks = TextElement.mergeWords(page.getText());
        return TextChunk.groupByLines(textChunks);
    }

    private Cut findCut(Page page, List<Line> lines) {
        // Horizontal spans of all non-empty text blocks inside this candidate area.
        List<float[]> spans = new ArrayList<>();
        for (Line line : lines) {
            for (TextChunk chunk : nonEmptyChunks(line)) {
                if (chunk.getRight() <= page.getLeft() || chunk.getLeft() >= page.getRight()) {
                    continue;
                }
                spans.add(new float[] {
                        Math.max(chunk.getLeft(), page.getLeft()),
                        Math.min(chunk.getRight(), page.getRight())
                });
            }
        }

        if (spans.isEmpty()) {
            return null;
        }

        Collections.sort(spans, new Comparator<float[]>() {
            @Override
            public int compare(float[] a, float[] b) {
                return Float.compare(a[0], b[0]);
            }
        });

        // Merge overlapping/touching spans: any x interval not covered by the
        // union is a vertical band that no text block crosses.
        List<float[]> occupied = new ArrayList<>();
        float[] current = spans.get(0).clone();
        for (int i = 1; i < spans.size(); i++) {
            float[] span = spans.get(i);
            if (span[0] <= current[1]) {
                current[1] = Math.max(current[1], span[1]);
            } else {
                occupied.add(current);
                current = span.clone();
            }
        }
        occupied.add(current);

        Cut best = null;
        for (int i = 0; i < occupied.size() - 1; i++) {
            float gapLeft = occupied.get(i)[1];
            float gapRight = occupied.get(i + 1)[0];
            float gapWidth = gapRight - gapLeft;

            if (gapWidth < minGapWidth) {
                continue;
            }

            float cutAt = gapLeft + gapWidth / 2f;
            if (countValidRows(lines, page.getLeft(), cutAt) >= minValidRows
                    && countValidRows(lines, cutAt, page.getRight()) >= minValidRows) {
                Cut candidate = new Cut(gapLeft, gapRight, cutAt);
                if (best == null || isPreferredOver(candidate, best)) {
                    best = candidate;
                }
            }
        }

        return best;
    }

    /** Widest band wins; on equal width the leftmost band (smallest x) wins. */
    private boolean isPreferredOver(Cut candidate, Cut current) {
        int byWidth = Float.compare(candidate.width(), current.width());
        if (byWidth != 0) {
            return byWidth > 0;
        }
        return candidate.at < current.at;
    }

    private int countValidRows(List<Line> lines, float leftBound, float rightBound) {
        int validRows = 0;
        for (Line line : lines) {
            int blocksOnSide = 0;
            for (TextChunk chunk : nonEmptyChunks(line)) {
                if (chunk.getLeft() >= leftBound && chunk.getRight() <= rightBound) {
                    blocksOnSide++;
                }
            }
            if (blocksOnSide >= minBlocksPerRow) {
                validRows++;
            }
        }
        return validRows;
    }

    private static List<TextChunk> nonEmptyChunks(Line line) {
        List<TextChunk> chunks = new ArrayList<>();
        for (TextChunk chunk : line.getTextElements()) {
            if (!chunk.isSameChar(Line.WHITE_SPACE_CHARS)) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }
}
