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

/**
 * Strawman API proposal where:
 *
 * There is a single generic handler interface to be called for any type of artio message.
 *
 * This handler could be registered against different types of Fix messages.
 *
 * Pros: simple, generic and flexible
 * Cons: not semantic, nearly all call sites megamorphic in practice, potentially slower
 */
package uk.co.real_logic.artio.otf;
