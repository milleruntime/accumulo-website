package tour;

import org.apache.accumulo.core.client.impl.Table;
import org.apache.accumulo.core.data.impl.KeyExtent;
import org.apache.hadoop.io.Text;

public class Tablet {
    KeyExtent extent;

    public KeyExtent getExtent() {
        return extent;
    }

    //Text endRow;
    public Tablet(Table.ID tableId, Text endRow, Text prevEndRow) {
        this.extent = new KeyExtent(tableId, endRow, prevEndRow);
    }

    @Override
    public String toString() {
        return "Tablet(ID:" + extent.getTableId() + "..Range=" + extent.getEndRow() + "->" + extent.getPrevEndRow() + ")";
    }
}
