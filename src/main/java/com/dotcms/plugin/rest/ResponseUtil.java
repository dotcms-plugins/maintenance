package com.dotcms.plugin.rest;

import com.liferay.util.StringPool;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;

/**
 * Just a helper to encapsulate AuthenticationResource functionality.
 * @author jsanca
 */
public class ResponseUtil implements Serializable {

    public static ResponseUtil INSTANCE =
            new ResponseUtil();


    private ResponseUtil() {

    }

    private final static byte [] BEGIN_RESPONSE_ENTITY_VIEW = ("{\"entity\":").getBytes(StandardCharsets.UTF_8);
    private final static byte [] START_OBJECT    = StringPool.OPEN_CURLY_BRACE.getBytes(StandardCharsets.UTF_8);
    private final static String  BEGIN_PROPERTY  = "\"{0}\":";
    private final static byte [] END_PROPERTY    = StringPool.CLOSE_CURLY_BRACE.getBytes(StandardCharsets.UTF_8);
    private final static byte [] EMPTY_END_RESPONSE_ENTITY_VIEW    =
            ("\"errors\": [],\n" +
            "    \"i18nMessagesMap\": {},\n" +
            "    \"messages\": [],\n" +
            "    \"permissions\": []").getBytes(StandardCharsets.UTF_8);


    public static void beginWrapResponseEntityView(final OutputStream outputStream, final boolean startObject) throws IOException {

        outputStream.write(BEGIN_RESPONSE_ENTITY_VIEW);
        if (startObject) {
            outputStream.write(START_OBJECT);
        }
    }

    public static void beginWrapProperty(final OutputStream outputStream, final String propertyName, final boolean startObject) throws IOException {

        outputStream.write(MessageFormat.format(BEGIN_PROPERTY, propertyName).getBytes(StandardCharsets.UTF_8));
        if (startObject) {
            outputStream.write(START_OBJECT);
        }
    }

    public static void endWrapProperty(final OutputStream outputStream) throws IOException {

        outputStream.write(END_PROPERTY);
    }

    public static void wrapProperty(final OutputStream outputStream, final String propertyName,
                                    final String valueOnJson) throws IOException {

        outputStream.write(MessageFormat.format("\"{0}\":", propertyName).getBytes(StandardCharsets.UTF_8));
        outputStream.write(valueOnJson.getBytes(StandardCharsets.UTF_8));
        outputStream.write(END_PROPERTY);
    }

    public static void endWrapResponseEntityView(final OutputStream outputStream, final boolean endObject) throws IOException {

        outputStream.write(EMPTY_END_RESPONSE_ENTITY_VIEW);
        if (endObject) {
            outputStream.write(END_PROPERTY);
        }
    }

} // E:O:F:ResponseUtil.
