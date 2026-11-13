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
package uk.co.real_logic.artio.system_tests;

import org.agrona.concurrent.EpochNanoClock;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.engine.framer.LibraryInfo;
import uk.co.real_logic.artio.library.FixLibrary;
import uk.co.real_logic.artio.library.LibraryConfiguration;
import uk.co.real_logic.artio.session.Session;

import java.util.List;
import java.util.function.Function;

import static uk.co.real_logic.artio.Timing.assertEventuallyTrue;
import static uk.co.real_logic.artio.system_tests.SystemTestUtil.*;

final class LibraryDriver implements AutoCloseable
{
    private final FakeOtfAcceptor otfAcceptor = new FakeOtfAcceptor();
    private final FakeHandler handler = new FakeHandler(otfAcceptor);
    private final FixLibrary library;
    private final TestSystem testSystem;

    static LibraryDriver accepting(final TestSystem testSystem, final EpochNanoClock nanoClock)
    {
        return new LibraryDriver(testSystem, sessionHandler ->
            SystemTestUtil.acceptingLibraryConfig(sessionHandler, nanoClock));
    }

    static LibraryDriver initiating(
        final int libraryAeronPort, final TestSystem testSystem, final EpochNanoClock nanoClock)
    {
        return new LibraryDriver(testSystem, handler -> initiatingLibraryConfig(libraryAeronPort, handler, nanoClock));
    }

    private LibraryDriver(final TestSystem testSystem, final Function<FakeHandler, LibraryConfiguration> configFactory)
    {
        this.testSystem = testSystem;

        final LibraryConfiguration configuration = configFactory.apply(handler);
        library = testSystem.connect(configuration);
    }

    long awaitSessionId()
    {
        return handler.awaitSessionId(this::poll);
    }

    SessionExistsInfo awaitCompleteSessionId()
    {
        return handler.awaitCompleteSessionId(this::poll);
    }

    @Override
    public void close()
    {
        testSystem.close(library);
    }

    void becomeOnlyLibraryConnectedTo(final FixEngine engine)
    {
        final int ourLibraryId = library.libraryId();
        assertEventuallyTrue(
            () -> ourLibraryId + " has failed to become the only library: " + libraries(engine),
            () ->
            {
                poll();

                final List<LibraryInfo> libraries = libraries(engine);
                return libraries.size() == 2 &&
                       libraryInfoById(libraries, ourLibraryId).isPresent();
            },
            5000,
            () -> {});
    }

    private void poll()
    {
        testSystem.poll();
    }

    public Session requestSession(final long sessionId)
    {
        return SystemTestUtil.acquireSession(handler, library, sessionId, testSystem);
    }
}
