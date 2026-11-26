/*
 * Copyright 2020 Monotonic Ltd.
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
package uk.co.real_logic.artio.admin;

import uk.co.real_logic.artio.CommandLineUtil;

import java.util.Scanner;

/**
 * Commandline tool that can be used by operations staff for controlling an Artio instance.
 *
 * Tool takes no arguments but Java System properties can be used in order to configure the connectivity to
 * a FixEngine instance.
 */
public final class ArtioAdminTool
{
    private static final String RESET_SEQUENCE_NUMBERS = "resetSequenceNumbers ";
    private static final String DISCONNECT = "disconnect ";

    public static void main(final String[] args)
    {
        final Scanner scanner = new Scanner(System.in);
        try (ArtioAdmin artioAdmin = ArtioAdmin.launch(new ArtioAdminConfiguration()))
        {
            System.out.println("Please type 'help' in order to see a list of available commands");

            while (true)
            {
                final String inputLine = scanner.nextLine();

                try
                {
                    if (inputLine.equals("help"))
                    {
                        printHelp();
                    }
                    else if (inputLine.equals("printAllSessions"))
                    {
                        artioAdmin.allFixSessions().forEach(System.out::println);
                    }
                    else if (inputLine.equals("exit"))
                    {
                        System.out.println("Bye");
                        System.exit(0);
                    }
                    else if (inputLine.startsWith(RESET_SEQUENCE_NUMBERS))
                    {
                        resetSequenceNumbers(artioAdmin, inputLine);
                    }
                    else if (inputLine.startsWith(DISCONNECT))
                    {
                        disconnectSession(artioAdmin, inputLine);
                    }
                    else
                    {
                        printUnknownCommand(inputLine);
                    }
                }
                catch (final IllegalArgumentException ex)
                {
                    System.out.println(ex.getMessage());
                    printHelp();
                }
                catch (final Throwable t)
                {
                    t.printStackTrace();
                }
            }
        }
    }

    private static void disconnectSession(final ArtioAdmin artioAdmin, final String inputLine)
    {
        final long sessionId = CommandLineUtil.parseLong(inputLine.substring(DISCONNECT.length()), "session id");
        artioAdmin.disconnectSession(sessionId);
        System.out.println("Disconnected " + sessionId);
    }

    private static void resetSequenceNumbers(final ArtioAdmin artioAdmin, final String inputLine)
    {
        final long sessionId = CommandLineUtil.parseLong(
            inputLine.substring(RESET_SEQUENCE_NUMBERS.length()), "session id");
        artioAdmin.resetSequenceNumbers(sessionId);
        System.out.println("Reset sequence numbers for " + sessionId);
    }

    private static void printUnknownCommand(final String inputLine)
    {
        System.out.println("Unknown command: '" + inputLine + '\'');
        printHelp();
    }

    private static void printHelp()
    {
        System.out.println("'help'                     - prints this output");
        System.out.println("'printAllSessions'         - prints a list of all the known FIX sessions");
        System.out.println("'resetSequenceNumbers <N>' - reset the sequence numbers of the session with sessionId=<N>");
        System.out.println("'disconnect <N>'           - disconnect the session with sessionId=<N>");
        System.out.println("'exit'                     - exit the tool");
    }
}
