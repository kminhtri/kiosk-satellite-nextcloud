// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.IOException;

final class ErrorMessages {
    static String connection(IOException error) {
        String message = error.getMessage();
        // Only client-authored messages may reach settings or the renderer, never raw network exceptions.
        return message != null && (message.startsWith("Nextcloud ") || message.startsWith("Photo limit")
                || message.startsWith("Folder limit") || message.startsWith("Unsupported or damaged"))
                ? message : "Cannot load Nextcloud photos. Check the connection and HTTPS certificate. Retrying in 30 seconds.";
    }
    private ErrorMessages() {}
}
