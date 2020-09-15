package gov.cms.ab2d.worker.processor;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface StreamHelper extends Closeable {
    void addData(byte[] data) throws IOException;
    void addError(String data) throws IOException;
    List<Path> getDataFiles();
    List<Path> getErrorFiles();
    void close() throws IOException;
}
