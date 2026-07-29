/*
 * Copyright 2015-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.dictionary.generation;

import uk.co.real_logic.artio.dictionary.ir.Field;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

final class OptionalSessionFields
{
    public static final String USERNAME = "Username";
    public static final String PASSWORD = "Password";
    public static final String CANCEL_ON_DISCONNECT_TYPE = "CancelOnDisconnectType";
    public static final String COD_TIMEOUT_WINDOW = "CODTimeoutWindow";
    public static final String REF_MSG_TYPE = "RefMsgType";

    static final Map<String, Field.Type> OPTIONAL_FIELD_TYPES = new HashMap<>();
    static final Map<String, List<String>> ENCODER_OPTIONAL_SESSION_FIELDS = new HashMap<>();
    static final Map<String, List<String>> DECODER_OPTIONAL_SESSION_FIELDS = new HashMap<>();

    static
    {
        OPTIONAL_FIELD_TYPES.put(USERNAME, Field.Type.STRING);
        OPTIONAL_FIELD_TYPES.put(PASSWORD, Field.Type.STRING);
        OPTIONAL_FIELD_TYPES.put(CANCEL_ON_DISCONNECT_TYPE, Field.Type.INT);
        OPTIONAL_FIELD_TYPES.put(COD_TIMEOUT_WINDOW, Field.Type.INT);
        OPTIONAL_FIELD_TYPES.put(REF_MSG_TYPE, Field.Type.STRING);

        final List<String> logonFields = asList(USERNAME, PASSWORD, CANCEL_ON_DISCONNECT_TYPE, COD_TIMEOUT_WINDOW);
        ENCODER_OPTIONAL_SESSION_FIELDS.put("LogonEncoder", logonFields);
        DECODER_OPTIONAL_SESSION_FIELDS.put("LogonDecoder", logonFields);

        final List<String> rejectFields = asList(REF_MSG_TYPE);
        ENCODER_OPTIONAL_SESSION_FIELDS.put("RejectEncoder", rejectFields);
    }
}
