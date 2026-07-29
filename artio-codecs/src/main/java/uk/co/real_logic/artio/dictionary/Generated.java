/*
 * Copyright 2019-2021 Monotonic Ltd.
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
package uk.co.real_logic.artio.dictionary;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the annotated member code was generated. Retained on the class,
 * for use with static analysis tooling.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@Documented
@SuppressWarnings("unused")
public @interface Generated
{
    /**
     * The value element MUST have the name of the code generator. The
     * name is the fully qualified name of the code generator.
     *
     * @return The name of the code generator
     */
    String[] value();

    /**
     * A place-holder for any comments that the code generator may want to
     * include in the generated code.
     *
     * @return Comments that the code generated included
     */
    String comments() default "";
}
