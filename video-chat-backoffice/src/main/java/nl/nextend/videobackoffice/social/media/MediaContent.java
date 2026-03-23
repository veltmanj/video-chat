package nl.nextend.videobackoffice.social.media;

/**
 * In-memory representation of a media download returned to the HTTP layer.
 */
public record MediaContent(String fileName, String mimeType, byte[] bytes) {
}
