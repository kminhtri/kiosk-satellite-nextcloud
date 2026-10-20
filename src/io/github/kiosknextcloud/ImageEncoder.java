// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.IOException;

interface ImageEncoder {
    byte[] encode(byte[] original) throws IOException;
}
