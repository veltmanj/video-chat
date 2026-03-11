package nl.nextend.videobackoffice.social;

import java.nio.file.Path;

public interface SocialMediaStorage {

    void putObject(String objectKey, Path file, String contentType);

    byte[] getObject(String objectKey);
}
