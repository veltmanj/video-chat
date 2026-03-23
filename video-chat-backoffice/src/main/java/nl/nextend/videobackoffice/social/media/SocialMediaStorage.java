package nl.nextend.videobackoffice.social.media;

import java.nio.file.Path;

/**
 * Minimal abstraction over blob storage used by the social media flows.
 *
 * <p>The service layer deals only in object keys and byte content so the backing implementation can
 * be swapped for tests or a different storage provider without changing media policy code.
 */
public interface SocialMediaStorage {

    void putObject(String objectKey, Path file, String contentType);

    byte[] getObject(String objectKey);
}
