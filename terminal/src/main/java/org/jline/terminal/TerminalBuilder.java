/*
 * Copyright (c) 2002-2016, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package org.jline.terminal;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ServiceLoader;

import org.jline.terminal.impl.AbstractPosixTerminal;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.terminal.impl.ExecPty;
import org.jline.terminal.impl.ExternalTerminal;
import org.jline.terminal.impl.PosixPtyTerminal;
import org.jline.terminal.impl.PosixSysTerminal;
import org.jline.terminal.spi.JansiSupport;
import org.jline.terminal.spi.JnaSupport;
import org.jline.terminal.spi.Pty;
import org.jline.utils.Log;
import org.jline.utils.OSUtils;

/**
 * Builder class to create terminals.
 */
public final class TerminalBuilder {

    //
    // System properties
    //

    public static final String PROP_ENCODING = "org.jline.terminal.encoding";
    public static final String PROP_CODEPAGE = "org.jline.terminal.codepage";
    public static final String PROP_TYPE = "org.jline.terminal.type";
    public static final String PROP_JNA = "org.jline.terminal.jna";
    public static final String PROP_JANSI = "org.jline.terminal.jansi";
    public static final String PROP_EXEC = "org.jline.terminal.exec";
    public static final String PROP_DUMB = "org.jline.terminal.dumb";

    /**
     * Returns the default system terminal.
     * Terminals should be closed properly using the {@link Terminal#close()}
     * method in order to restore the original terminal state.
     *
     * This call is equivalent to:
     * <code>builder().build()</code>
     */
    public static Terminal terminal() throws IOException {
        return builder().build();
    }

    /**
     * Creates a new terminal builder instance.
     */
    public static TerminalBuilder builder() {
        return new TerminalBuilder();
    }

    private String name;
    private InputStream in;
    private OutputStream out;
    private String type;
    private String encoding;
    private int codepage;
    private Boolean system;
    private Boolean jna;
    private Boolean jansi;
    private Boolean exec;
    private Boolean dumb;
    private Attributes attributes;
    private Size size;
    private boolean nativeSignals = false;
    private Terminal.SignalHandler signalHandler = Terminal.SignalHandler.SIG_DFL;

    private TerminalBuilder() {
    }

    public TerminalBuilder name(String name) {
        this.name = name;
        return this;
    }

    public TerminalBuilder streams(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
        return this;
    }

    public TerminalBuilder system(boolean system) {
        this.system = system;
        return this;
    }

    public TerminalBuilder jna(boolean jna) {
        this.jna = jna;
        return this;
    }

    public TerminalBuilder jansi(boolean jansi) {
        this.jansi = jansi;
        return this;
    }

    public TerminalBuilder exec(boolean exec) {
        this.exec = exec;
        return this;
    }

    public TerminalBuilder dumb(boolean dumb) {
        this.dumb = dumb;
        return this;
    }

    public TerminalBuilder type(String type) {
        this.type = type;
        return this;
    }

    public TerminalBuilder encoding(String encoding) {
        this.encoding = encoding;
        return this;
    }

    public TerminalBuilder codepage(int codepage) {
        this.codepage = codepage;
        return this;
    }

    /**
     * Attributes to use when creating a non system terminal,
     * i.e. when the builder has been given the input and
     * outut streams using the {@link #streams(InputStream, OutputStream)} method
     * or when {@link #system(boolean)} has been explicitely called with
     * <code>false</code>.
     *
     * @see #size(Size)
     * @see #system(boolean)
     */
    public TerminalBuilder attributes(Attributes attributes) {
        this.attributes = attributes;
        return this;
    }

    /**
     * Initial size to use when creating a non system terminal,
     * i.e. when the builder has been given the input and
     * outut streams using the {@link #streams(InputStream, OutputStream)} method
     * or when {@link #system(boolean)} has been explicitely called with
     * <code>false</code>.
     *
     * @see #attributes(Attributes)
     * @see #system(boolean)
     */
    public TerminalBuilder size(Size size) {
        this.size = size;
        return this;
    }

    public TerminalBuilder nativeSignals(boolean nativeSignals) {
        this.nativeSignals = nativeSignals;
        return this;
    }

    public TerminalBuilder signalHandler(Terminal.SignalHandler signalHandler) {
        this.signalHandler = signalHandler;
        return this;
    }

    public Terminal build() throws IOException {
        Terminal terminal = doBuild();
        Log.debug(() -> "Using terminal " + terminal.getClass().getSimpleName());
        if (terminal instanceof AbstractPosixTerminal) {
            Log.debug(() -> "Using pty " + ((AbstractPosixTerminal) terminal).getPty().getClass().getSimpleName());
        }
        return terminal;
    }

    private Terminal doBuild() throws IOException {
        String name = this.name;
        if (name == null) {
            name = "JLine terminal";
        }
        String encoding = this.encoding;
        if (encoding == null) {
            encoding = System.getProperty(PROP_ENCODING);
        }
        if (encoding == null) {
            encoding = Charset.defaultCharset().name();
        }
        int codepage = this.codepage;
        if (codepage <= 0) {
            String str = System.getProperty(PROP_CODEPAGE);
            if (str != null) {
                codepage = Integer.parseInt(str);
            }
        }
        String type = this.type;
        if (type == null) {
            type = System.getProperty(PROP_TYPE);
        }
        if (type == null) {
            type = System.getenv("TERM");
        }
        Boolean jna = this.jna;
        if (jna == null) {
            jna = getBoolean(PROP_JNA, true);
        }
        Boolean jansi = this.jansi;
        if (jansi == null) {
            jansi = getBoolean(PROP_JANSI, true);
        }
        Boolean exec = this.exec;
        if (exec == null) {
            exec = getBoolean(PROP_EXEC, true);
        }
        Boolean dumb = this.dumb;
        if (dumb == null) {
            dumb = getBoolean(PROP_DUMB, null);
        }
        if ((system != null && system) || (system == null && in == null && out == null)) {
            if (attributes != null || size != null) {
                Log.warn("Attributes and size fields are ignored when creating a system terminal");
            }
            IllegalStateException exception = new IllegalStateException("Unable to create a system terminal");
            //
            // Cygwin support
            //
            if (OSUtils.IS_CYGWIN || OSUtils.IS_MINGW) {
                if (exec) {
                    try {
                        Pty pty = ExecPty.current();
                        // Cygwin defaults to XTERM, but actually supports 256 colors,
                        // so if the value comes from the environment, change it to xterm-256color
                        if ("xterm".equals(type) && this.type == null && System.getProperty(PROP_TYPE) == null) {
                            type = "xterm-256color";
                        }
                        return new PosixSysTerminal(name, type, pty, encoding, nativeSignals, signalHandler);
                    } catch (IOException e) {
                        // Ignore if not a tty
                        Log.debug("Error creating EXEC based terminal: ", e.getMessage(), e);
                        exception.addSuppressed(e);
                    }
                }
            }
            else if (OSUtils.IS_WINDOWS) {
                if (jna) {
                    try {
                        return load(JnaSupport.class).winSysTerminal(name, codepage, nativeSignals, signalHandler);
                    } catch (Throwable t) {
                        Log.debug("Error creating JNA based terminal: ", t.getMessage(), t);
                        exception.addSuppressed(t);
                    }
                }
                if (jansi) {
                    try {
                        return load(JansiSupport.class).winSysTerminal(name, codepage, nativeSignals, signalHandler);
                    } catch (Throwable t) {
                        Log.debug("Error creating JANSI based terminal: ", t.getMessage(), t);
                        exception.addSuppressed(t);
                    }
                }
            } else {
                if (jna) {
                    try {
                        Pty pty = load(JnaSupport.class).current();
                        return new PosixSysTerminal(name, type, pty, encoding, nativeSignals, signalHandler);
                    } catch (Throwable t) {
                        // ignore
                        Log.debug("Error creating JNA based terminal: ", t.getMessage(), t);
                        exception.addSuppressed(t);
                    }
                }
                if (jansi) {
                    try {
                        Pty pty = load(JansiSupport.class).current();
                        return new PosixSysTerminal(name, type, pty, encoding, nativeSignals, signalHandler);
                    } catch (Throwable t) {
                        Log.debug("Error creating JANSI based terminal: ", t.getMessage(), t);
                        exception.addSuppressed(t);
                    }
                }
                if (exec) {
                    try {
                        Pty pty = ExecPty.current();
                        return new PosixSysTerminal(name, type, pty, encoding, nativeSignals, signalHandler);
                    } catch (Throwable t) {
                        // Ignore if not a tty
                        Log.debug("Error creating EXEC based terminal: ", t.getMessage(), t);
                        exception.addSuppressed(t);
                    }
                }
            }
            if (dumb == null || dumb) {
                if (dumb == null) {
                    if (Log.isDebugEnabled()) {
                        Log.warn("Creating a dumb terminal", exception);
                    } else {
                        Log.warn("Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)");
                    }
                }
                return new DumbTerminal(name, type != null ? type : Terminal.TYPE_DUMB,
                        new FileInputStream(FileDescriptor.in),
                        new FileOutputStream(FileDescriptor.out),
                        encoding, signalHandler);
            } else {
                throw exception;
            }
        } else {
            if (jna) {
                try {
                    Pty pty = load(JnaSupport.class).open(attributes, size);
                    return new PosixPtyTerminal(name, type, pty, in, out, encoding, signalHandler);
                } catch (Throwable t) {
                    Log.debug("Error creating JNA based terminal: ", t.getMessage(), t);
                }
            }
            if (jansi) {
                try {
                    Pty pty = load(JansiSupport.class).open(attributes, size);
                    return new PosixPtyTerminal(name, type, pty, in, out, encoding, signalHandler);
                } catch (Throwable t) {
                    Log.debug("Error creating JANSI based terminal: ", t.getMessage(), t);
                }
            }
            Terminal terminal = new ExternalTerminal(name, type, in, out, encoding, signalHandler);
            if (attributes != null) {
                terminal.setAttributes(attributes);
            }
            if (size != null) {
                terminal.setSize(size);
            }
            return terminal;
        }
    }

    private static Boolean getBoolean(String name, Boolean def) {
        try {
            String str = System.getProperty(name);
            if (str != null) {
                return Boolean.parseBoolean(str);
            }
        } catch (IllegalArgumentException | NullPointerException e) {
        }
        return def;
    }

    private <S> S load(Class<S> clazz) {
        return ServiceLoader.load(clazz, clazz.getClassLoader()).iterator().next();
    }
}
