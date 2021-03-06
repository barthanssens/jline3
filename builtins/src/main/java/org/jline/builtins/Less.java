/*
 * Copyright (c) 2002-2018, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jline.builtins.Source.ResourceSource;
import org.jline.builtins.Source.URLSource;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.Terminal.SignalHandler;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Status;

import static org.jline.keymap.KeyMap.alt;
import static org.jline.keymap.KeyMap.ctrl;
import static org.jline.keymap.KeyMap.del;
import static org.jline.keymap.KeyMap.key;

public class Less {

    private static final int ESCAPE = 27;
    private static final String MESSAGE_FILE_INFO = "FILE_INFO";

    public boolean quitAtSecondEof;
    public boolean quitAtFirstEof;
    public boolean quitIfOneScreen;
    public boolean printLineNumbers;
    public boolean quiet;
    public boolean veryQuiet;
    public boolean chopLongLines;
    public boolean ignoreCaseCond;
    public boolean ignoreCaseAlways;
    public boolean noKeypad;
    public boolean noInit;
    
    protected List<Integer> tabs = Arrays.asList(4);
    protected final Terminal terminal;
    protected final Display display;
    protected final BindingReader bindingReader;
    protected final Path currentDir;

    protected List<Source> sources;
    protected int sourceIdx;
    protected BufferedReader reader;
    protected KeyMap<Operation> keys;

    protected int firstLineInMemory = 0;
    protected List<AttributedString> lines = new ArrayList<>();

    protected int firstLineToDisplay = 0;
    protected int firstColumnToDisplay = 0;
    protected int offsetInLine = 0;

    protected String message;
    protected final StringBuilder buffer = new StringBuilder();

    protected final Map<String, Operation> options = new TreeMap<>();

    protected int window;
    protected int halfWindow;

    protected int nbEof;

    protected List<String> patterns = new ArrayList<String>();
    protected int patternId = -1;
    protected String pattern;
    protected String displayPattern;

    protected final Size size = new Size();


    public Less(Terminal terminal, Path currentDir) {
        this.terminal = terminal;
        this.display = new Display(terminal, true);
        this.bindingReader = new BindingReader(terminal.reader());
        this.currentDir = currentDir;
    }

    public Less tabs(List<Integer> tabs) {
        this.tabs = tabs;
        return this;
    }

    public void handle(Signal signal) {
        size.copy(terminal.getSize());
        try {
            display.clear();
            display(false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void run(Source... sources) throws IOException, InterruptedException {
        run(Arrays.asList(sources));
    }

    public void run(List<Source> sources) throws IOException, InterruptedException {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("No sources");
        }
        sources.add(0, new ResourceSource("less-help.txt", "HELP -- Press SPACE for more, or q when done"));
        this.sources = sources;

        sourceIdx = 1;
        openSource();
        Status status = Status.getStatus(terminal, false);

        try {
            if (status != null) {
                status.suspend();
            }
            size.copy(terminal.getSize());

            if (quitIfOneScreen && sources.size() == 2) {
                if (display(true)) {
                    return;
                }
            }

            SignalHandler prevHandler = terminal.handle(Signal.WINCH, this::handle);
            Attributes attr = terminal.enterRawMode();
            try {
                window = size.getRows() - 1;
                halfWindow = window / 2;
                keys = new KeyMap<>();
                bindKeys(keys);

                // Use alternate buffer
                if (!noInit) {
                    terminal.puts(Capability.enter_ca_mode);
                }
                if (!noKeypad) {
                    terminal.puts(Capability.keypad_xmit);
                }
                terminal.writer().flush();

                display.clear();
                display(false);
                checkInterrupted();

                options.put("-e", Operation.OPT_QUIT_AT_SECOND_EOF);
                options.put("--quit-at-eof", Operation.OPT_QUIT_AT_SECOND_EOF);
                options.put("-E", Operation.OPT_QUIT_AT_FIRST_EOF);
                options.put("-QUIT-AT-EOF", Operation.OPT_QUIT_AT_FIRST_EOF);
                options.put("-N", Operation.OPT_PRINT_LINES);
                options.put("--LINE-NUMBERS", Operation.OPT_PRINT_LINES);
                options.put("-q", Operation.OPT_QUIET);
                options.put("--quiet", Operation.OPT_QUIET);
                options.put("--silent", Operation.OPT_QUIET);
                options.put("-Q", Operation.OPT_VERY_QUIET);
                options.put("--QUIET", Operation.OPT_VERY_QUIET);
                options.put("--SILENT", Operation.OPT_VERY_QUIET);
                options.put("-S", Operation.OPT_CHOP_LONG_LINES);
                options.put("--chop-long-lines", Operation.OPT_CHOP_LONG_LINES);
                options.put("-i", Operation.OPT_IGNORE_CASE_COND);
                options.put("--ignore-case", Operation.OPT_IGNORE_CASE_COND);
                options.put("-I", Operation.OPT_IGNORE_CASE_ALWAYS);
                options.put("--IGNORE-CASE", Operation.OPT_IGNORE_CASE_ALWAYS);

                Operation op;
                boolean forward = true;
                do {
                    checkInterrupted();

                    op = null;
                    //
                    // Option edition
                    //
                    if (buffer.length() > 0 && buffer.charAt(0) == '-') {
                        int c = terminal.reader().read();
                        message = null;
                        if (buffer.length() == 1) {
                            buffer.append((char) c);
                            if (c != '-') {
                                op = options.get(buffer.toString());
                                if (op == null) {
                                    message = "There is no " + printable(buffer.toString()) + " option";
                                    buffer.setLength(0);
                                }
                            }
                        } else if (c == '\r') {
                            op = options.get(buffer.toString());
                            if (op == null) {
                                message = "There is no " + printable(buffer.toString()) + " option";
                                buffer.setLength(0);
                            }
                        } else {
                            buffer.append((char) c);
                            Map<String, Operation> matching = new HashMap<>();
                            for (Map.Entry<String, Operation> entry : options.entrySet()) {
                                if (entry.getKey().startsWith(buffer.toString())) {
                                    matching.put(entry.getKey(), entry.getValue());
                                }
                            }
                            switch (matching.size()) {
                                case 0:
                                    buffer.setLength(0);
                                    break;
                                case 1:
                                    buffer.setLength(0);
                                    buffer.append(matching.keySet().iterator().next());
                                    break;
                            }
                        }
                    }
                    //
                    // Pattern edition
                    //
                    else if (buffer.length() > 0 && (buffer.charAt(0) == '/' || buffer.charAt(0) == '?' || buffer.charAt(0) == '&')) {
                        forward = search();
                    }
                    //
                    // Command reading
                    //
                    else {
                        Operation obj = bindingReader.readBinding(keys, null, false);
                        if (obj == Operation.CHAR) {
                            char c = bindingReader.getLastBinding().charAt(0);
                            // Enter option mode or pattern edit mode
                            if (c == '-' || c == '/' || c == '?' || c == '&') {
                                buffer.setLength(0);
                            }
                            buffer.append(c);
                        } else if (obj == Operation.BACKSPACE) {
                            if (buffer.length() > 0) {
                                buffer.deleteCharAt(buffer.length() - 1);
                            }
                        } else {
                            op = obj;
                        }
                    }
                    if (op != null) {
                        message = null;
                        switch (op) {
                            case FORWARD_ONE_LINE:
                                moveForward(getStrictPositiveNumberInBuffer(1));
                                break;
                            case BACKWARD_ONE_LINE:
                                moveBackward(getStrictPositiveNumberInBuffer(1));
                                break;
                            case FORWARD_ONE_WINDOW_OR_LINES:
                                moveForward(getStrictPositiveNumberInBuffer(window));
                                break;
                            case FORWARD_ONE_WINDOW_AND_SET:
                                window = getStrictPositiveNumberInBuffer(window);
                                moveForward(window);
                                break;
                            case FORWARD_ONE_WINDOW_NO_STOP:
                                moveForward(window);
                                // TODO: handle no stop
                                break;
                            case FORWARD_HALF_WINDOW_AND_SET:
                                halfWindow = getStrictPositiveNumberInBuffer(halfWindow);
                                moveForward(halfWindow);
                                break;
                            case BACKWARD_ONE_WINDOW_AND_SET:
                                window = getStrictPositiveNumberInBuffer(window);
                                moveBackward(window);
                                break;
                            case BACKWARD_ONE_WINDOW_OR_LINES:
                                moveBackward(getStrictPositiveNumberInBuffer(window));
                                break;
                            case BACKWARD_HALF_WINDOW_AND_SET:
                                halfWindow = getStrictPositiveNumberInBuffer(halfWindow);
                                moveBackward(halfWindow);
                                break;
                            case GO_TO_FIRST_LINE_OR_N:
                                moveTo(getStrictPositiveNumberInBuffer(1) - 1);
                                break;
                            case GO_TO_LAST_LINE_OR_N:
                                int lineNum = getStrictPositiveNumberInBuffer(0) - 1;
                                if (lineNum < 0) {
                                    moveForward(Integer.MAX_VALUE);
                                } else {
                                    moveTo(lineNum);
                                }
                                break;
                            case HOME:
                                moveTo(0);
                                break;
                            case END:
                                moveForward(Integer.MAX_VALUE);
                                break;
                            case LEFT_ONE_HALF_SCREEN:
                                firstColumnToDisplay = Math.max(0, firstColumnToDisplay - size.getColumns() / 2);
                                break;
                            case RIGHT_ONE_HALF_SCREEN:
                                firstColumnToDisplay += size.getColumns() / 2;
                                break;
                            case REPEAT_SEARCH_BACKWARD:
                            case REPEAT_SEARCH_BACKWARD_SPAN_FILES:
                                if (forward) {
                                    moveToPreviousMatch();
                                } else {
                                    moveToNextMatch();
                                }
                                break;
                            case REPEAT_SEARCH_FORWARD:
                            case REPEAT_SEARCH_FORWARD_SPAN_FILES:
                                if (forward) {
                                    moveToNextMatch();
                                } else {
                                    moveToPreviousMatch();
                                }
                                break;
                            case UNDO_SEARCH:
                                pattern = null;
                                break;
                            case OPT_PRINT_LINES:
                                buffer.setLength(0);
                                printLineNumbers = !printLineNumbers;
                                message = printLineNumbers ? "Constantly display line numbers" : "Don't use line numbers";
                                break;
                            case OPT_QUIET:
                                buffer.setLength(0);
                                quiet = !quiet;
                                veryQuiet = false;
                                message = quiet ? "Ring the bell for errors but not at eof/bof" : "Ring the bell for errors AND at eof/bof";
                                break;
                            case OPT_VERY_QUIET:
                                buffer.setLength(0);
                                veryQuiet = !veryQuiet;
                                quiet = false;
                                message = veryQuiet ? "Never ring the bell" : "Ring the bell for errors AND at eof/bof";
                                break;
                            case OPT_CHOP_LONG_LINES:
                                buffer.setLength(0);
                                offsetInLine = 0;
                                chopLongLines = !chopLongLines;
                                message = chopLongLines ? "Chop long lines" : "Fold long lines";
                                break;
                            case OPT_IGNORE_CASE_COND:
                                ignoreCaseCond = !ignoreCaseCond;
                                ignoreCaseAlways = false;
                                message = ignoreCaseCond ? "Ignore case in searches" : "Case is significant in searches";
                                break;
                            case OPT_IGNORE_CASE_ALWAYS:
                                ignoreCaseAlways = !ignoreCaseAlways;
                                ignoreCaseCond = false;
                                message = ignoreCaseAlways ? "Ignore case in searches and in patterns" : "Case is significant in searches";
                                break;
                            case ADD_FILE:
                                addFile();
                                break;
                            case NEXT_FILE:
                                int next = getStrictPositiveNumberInBuffer(1);
                                if (sourceIdx < sources.size() - next) {
                                    SavedSourcePositions ssp = new SavedSourcePositions();
                                    sourceIdx += next;
                                    String newSource = sources.get(sourceIdx).getName();
                                    try {
                                        openSource();
                                    } catch (FileNotFoundException exp) {
                                        ssp.restore(newSource);
                                    }
                                } else {
                                    message = "No next file";
                                }
                                break;
                            case PREV_FILE:
                                int prev = getStrictPositiveNumberInBuffer(1);
                                if (sourceIdx > prev) {
                                    SavedSourcePositions ssp = new SavedSourcePositions(-1);
                                    sourceIdx -= prev;
                                    String newSource = sources.get(sourceIdx).getName();
                                    try {
                                        openSource();
                                    } catch (FileNotFoundException exp) {
                                        ssp.restore(newSource);
                                    }
                                } else {
                                    message = "No previous file";
                                }
                                break;
                            case GOTO_FILE:
                                int tofile = getStrictPositiveNumberInBuffer(1);
                                if (tofile < sources.size()) {
                                    SavedSourcePositions ssp = new SavedSourcePositions(tofile < sourceIdx ? -1 : 0);                                    
                                    sourceIdx = tofile;
                                    String newSource = sources.get(sourceIdx).getName();
                                    try {
                                        openSource();
                                    } catch (FileNotFoundException exp) {
                                        ssp.restore(newSource);
                                    }
                                } else {
                                    message = "No such file";
                                }
                                break;
                            case INFO_FILE:
                                message = MESSAGE_FILE_INFO;
                                break;
                            case DELETE_FILE:
                                if (sources.size() > 2) {
                                    sources.remove(sourceIdx);
                                    if (sourceIdx >= sources.size()) {
                                        sourceIdx = sources.size() - 1; 
                                    }
                                    openSource();
                                }
                                break;
                            case REPAINT:
                                size.copy(terminal.getSize());
                                display.clear();
                                break;
                            case REPAINT_AND_DISCARD:
                                message = null;
                                size.copy(terminal.getSize());
                                display.clear();
                                break;
                            case HELP:
                                help();
                                break;
                        }
                        buffer.setLength(0);
                    }
                    if (quitAtFirstEof && nbEof > 0 || quitAtSecondEof && nbEof > 1) {
                        if (sourceIdx < sources.size() - 1) {
                            sourceIdx++;
                            openSource();
                        } else {
                            op = Operation.EXIT;
                        }
                    }
                    display(false);
                } while (op != Operation.EXIT);
            } catch (InterruptedException ie) {
                // Do nothing
            } finally {
                terminal.setAttributes(attr);
                if (prevHandler != null) {
                    terminal.handle(Terminal.Signal.WINCH, prevHandler);
                }
                // Use main buffer
                if (!noInit) {
                    terminal.puts(Capability.exit_ca_mode);
                }
                if (!noKeypad) {
                    terminal.puts(Capability.keypad_local);
                }
                terminal.writer().flush();
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
            if (status != null) {
                status.restore();
            }
        }
    }

    private class LineEditor {
        private int begPos;

        public LineEditor(int begPos) {
            this.begPos = begPos;
        }

        public int editBuffer(Operation op, int curPos) {
            switch (op) {
            case INSERT:
                buffer.insert(curPos++, bindingReader.getLastBinding());
                break;
            case BACKSPACE:
                if (curPos > begPos - 1) {
                    buffer.deleteCharAt(--curPos);
                }
                break;
            case NEXT_WORD:
                int newPos = buffer.length();
                for (int i = curPos; i < buffer.length(); i++) {
                    if (buffer.charAt(i) == ' ') {
                        newPos = i + 1;
                        break;
                    }
                }
                curPos = newPos;
                break;
            case PREV_WORD:
                newPos = begPos;
                for (int i = curPos - 2; i > begPos; i--) {
                    if (buffer.charAt(i) == ' ') {
                        newPos = i + 1;
                        break;
                    }
                }
                curPos = newPos;
                break;
            case HOME:
                curPos = begPos;
                break;
            case END:
                curPos = buffer.length();
                break;
            case DELETE:
                if (curPos >= begPos && curPos < buffer.length()) {
                    buffer.deleteCharAt(curPos);
                }
                break;
            case DELETE_WORD:
                while (true) {
                    if(curPos < buffer.length() && buffer.charAt(curPos) != ' '){
                        buffer.deleteCharAt(curPos);
                    } else {
                        break;
                    }
                }
                while (true) {
                    if(curPos - 1 >= begPos) {
                        if (buffer.charAt(curPos - 1) != ' ') {
                            buffer.deleteCharAt(--curPos);
                        } else {
                            buffer.deleteCharAt(--curPos);
                            break;
                        }
                    } else {
                        break;
                    }
                }
                break;
            case DELETE_LINE:
                buffer.setLength(begPos);
                curPos = 1;
                break;
            case LEFT:
                if (curPos > begPos) {
                    curPos--;
                }
                break;
            case RIGHT:
                if (curPos < buffer.length()) {
                    curPos++;
                }
                break;
            }
            return curPos;
        }
    }
    
    private class SavedSourcePositions {
        int saveSourceIdx;
        int saveFirstLineToDisplay;
        int saveFirstColumnToDisplay;
        int saveOffsetInLine;
        boolean savePrintLineNumbers;
        
        public SavedSourcePositions (){
            this(0);
        }
        public SavedSourcePositions (int dec){
            saveSourceIdx = sourceIdx + dec;
            saveFirstLineToDisplay = firstLineToDisplay;
            saveFirstColumnToDisplay = firstColumnToDisplay;
            saveOffsetInLine = offsetInLine;
            savePrintLineNumbers = printLineNumbers;
        }
        
        public void restore(String failingSource) throws IOException {
            sourceIdx = saveSourceIdx;
            openSource();
            firstLineToDisplay = saveFirstLineToDisplay;
            firstColumnToDisplay = saveFirstColumnToDisplay;
            offsetInLine = saveOffsetInLine;
            printLineNumbers = savePrintLineNumbers;
            if (failingSource != null) {
                message = failingSource + " not found!";
            }
        }      
    }

    private void addSource(String file) throws IOException {
        if (file.contains("*") || file.contains("?")) {
            PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:"+file);
            Files.find(currentDir, Integer.MAX_VALUE, (path, f) -> pathMatcher.matches(path))
                 .forEach(p -> sources.add(Commands.doUrlSource(currentDir, p)));
        } else {
            sources.add(new URLSource(currentDir.resolve(file).toUri().toURL(), file));
        }
        sourceIdx = sources.size() - 1;
    }
    
    private void addFile() throws IOException, InterruptedException {
        KeyMap<Operation> fileKeyMap = new KeyMap<>();
        fileKeyMap.setUnicode(Operation.INSERT);
        for (char i = 32; i < 256; i++) {
            fileKeyMap.bind(Operation.INSERT, Character.toString(i));
        }
        fileKeyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right), alt('l'));
        fileKeyMap.bind(Operation.LEFT, key(terminal, Capability.key_left), alt('h'));
        fileKeyMap.bind(Operation.HOME, key(terminal, Capability.key_home), alt('0'));
        fileKeyMap.bind(Operation.END, key(terminal, Capability.key_end), alt('$'));
        fileKeyMap.bind(Operation.BACKSPACE, del());
        fileKeyMap.bind(Operation.DELETE, alt('x'));         
        fileKeyMap.bind(Operation.DELETE_WORD, alt('X'));
        fileKeyMap.bind(Operation.DELETE_LINE, ctrl('U'));
        fileKeyMap.bind(Operation.ACCEPT, "\r");

        SavedSourcePositions ssp = new SavedSourcePositions();
        message = null;
        buffer.append("Examine: ");
        int curPos = buffer.length();
        final int begPos = curPos;
        display(false, curPos);
        LineEditor lineEditor = new LineEditor(begPos);
        while (true) {
            checkInterrupted();
            Operation op = null;
            switch (op=bindingReader.readBinding(fileKeyMap)) {
            case ACCEPT:
                String name = buffer.toString().substring(begPos);
                addSource(name);
                try {
                    openSource();
                } catch (Exception exp) {
                    ssp.restore(name);
                }
                return;
            default:
                curPos = lineEditor.editBuffer(op, curPos);
                break;
            }
            if (curPos > begPos) {
                display(false, curPos);
            } else {
                buffer.setLength(0);
                return;
            }
        }
    }

    private boolean search() throws IOException, InterruptedException {
        KeyMap<Operation> searchKeyMap = new KeyMap<>();
        searchKeyMap.setUnicode(Operation.INSERT);
        for (char i = 32; i < 256; i++) {
            searchKeyMap.bind(Operation.INSERT, Character.toString(i));
        }
        searchKeyMap.bind(Operation.RIGHT, key(terminal, Capability.key_right), alt('l'));
        searchKeyMap.bind(Operation.LEFT, key(terminal, Capability.key_left), alt('h'));
        searchKeyMap.bind(Operation.NEXT_WORD, alt('w'));
        searchKeyMap.bind(Operation.PREV_WORD, alt('b'));
        searchKeyMap.bind(Operation.HOME, key(terminal, Capability.key_home), alt('0'));
        searchKeyMap.bind(Operation.END, key(terminal, Capability.key_end), alt('$'));
        searchKeyMap.bind(Operation.BACKSPACE, del());
        searchKeyMap.bind(Operation.DELETE, alt('x'));         
        searchKeyMap.bind(Operation.DELETE_WORD, alt('X'));
        searchKeyMap.bind(Operation.DELETE_LINE, ctrl('U'));
        searchKeyMap.bind(Operation.UP, key(terminal, Capability.key_up), alt('k'));
        searchKeyMap.bind(Operation.DOWN, key(terminal, Capability.key_down), alt('j'));        
        searchKeyMap.bind(Operation.ACCEPT, "\r");

        boolean forward = true;
        message = null;
        int curPos = buffer.length();
        final int begPos = curPos;
        final char type = buffer.charAt(0);
        String currentBuffer = "";
        LineEditor lineEditor = new LineEditor(begPos);
        while (true) {
            checkInterrupted();
            Operation op = null;
            switch (op=bindingReader.readBinding(searchKeyMap)) {
            case UP:
                patternId++;
                if (patternId >= 0 && patternId < patterns.size()) {
                    if (patternId == 0) {
                        currentBuffer = buffer.toString();
                    }
                    buffer.setLength(0);
                    buffer.append(type);
                    buffer.append(patterns.get(patternId));
                    curPos = buffer.length();
                } else if (patternId >= patterns.size()) {
                    patternId = patterns.size() - 1;
                }
                break;
            case DOWN:
                if (patterns.size() > 0) {
                    patternId--;
                    buffer.setLength(0);
                    if (patternId < 0) {
                        patternId = -1;
                        buffer.append(currentBuffer);                                    
                    } else {
                        buffer.append(type);
                        buffer.append(patterns.get(patternId));
                    }
                    curPos = buffer.length();
                }
                break;
            case ACCEPT:
                try {
                    String _pattern = buffer.toString().substring(1);
                    if (type == '&') {
                        displayPattern = _pattern.length() > 0 ? _pattern : null;
                        getPattern(true);
                    } else {
                        pattern = _pattern;
                        getPattern();
                        if (type == '/') {
                            moveToNextMatch();
                        } else {
                            if (lines.size() - firstLineToDisplay <= size.getRows() ) {
                                firstLineToDisplay = lines.size();
                            } else {
                                moveForward(size.getRows() - 1);
                            }
                            moveToPreviousMatch();
                            forward = false;
                        }
                    }
                    if (_pattern.length() > 0 && !patterns.contains(_pattern)) {
                        patterns.add(_pattern);
                    }
                    patternId = -1;
                    buffer.setLength(0);
                } catch (PatternSyntaxException e) {
                    String str = e.getMessage();
                    if (str.indexOf('\n') > 0) {
                        str = str.substring(0, str.indexOf('\n'));
                    }
                    if (type == '&') {
                        displayPattern = null;
                    } else {
                        pattern = null;
                    }
                    buffer.setLength(0);
                    message = "Invalid pattern: " + str + " (Press a key)";
                    display(false);
                    terminal.reader().read();
                    message = null;
                }
                return forward;
            default:
                curPos = lineEditor.editBuffer(op, curPos);
                break;
            }
            if (curPos < begPos) {
                buffer.setLength(0);
                return forward;
            } else {
                display(false, curPos);
            }
        }
    }
    
    private void help() throws IOException {
        SavedSourcePositions ssp = new SavedSourcePositions();
        printLineNumbers = false;
        sourceIdx = 0;
        try {
            openSource();
            display(false);
            Operation op = null;
            do {
                checkInterrupted();
                op = bindingReader.readBinding(keys, null, false);
                if (op != null) {
                    switch (op) {
                    case FORWARD_ONE_WINDOW_OR_LINES:
                        moveForward(getStrictPositiveNumberInBuffer(window));
                        break;
                    case BACKWARD_ONE_WINDOW_OR_LINES:
                        moveBackward(getStrictPositiveNumberInBuffer(window));
                        break;
                    }
                }
                display(false);
            } while (op != Operation.EXIT);
        } catch (IOException|InterruptedException exp) {
            // Do nothing
        } finally {
            ssp.restore(null);
        }
    }
     
    protected void openSource() throws IOException {
        boolean wasOpen = false;
        if (reader != null) {
            reader.close();
            wasOpen = true;
        }
        boolean open = false;
        boolean displayMessage = false; 
        do {
            Source source = sources.get(sourceIdx);
            try {
                InputStream in = source.read();
                if (sources.size() == 2 || sourceIdx == 0) {
                    message = source.getName();
                } else {
                    message = source.getName() + " (file " + sourceIdx + " of "
                            + (sources.size() - 1) + ")";
                }
                reader = new BufferedReader(new InputStreamReader(
                        new InterruptibleInputStream(in)));
                firstLineInMemory = 0;
                lines = new ArrayList<>();
                firstLineToDisplay = 0;
                firstColumnToDisplay = 0;
                offsetInLine = 0;
                display.clear();
                open = true;
                if (displayMessage) {
                    AttributedStringBuilder asb = new AttributedStringBuilder();
                    asb.style(AttributedStyle.INVERSE);
                    asb.append(source.getName() + " (press RETURN)");
                    asb.toAttributedString().println(terminal);
                    terminal.writer().flush();
                    terminal.reader().read();
                }
            } catch (FileNotFoundException exp) {
                sources.remove(sourceIdx);
                if (sourceIdx > sources.size() - 1) {
                    sourceIdx = sources.size() - 1;
                }
                if (wasOpen) {
                    throw exp;
                } else {
                    AttributedStringBuilder asb = new AttributedStringBuilder();
                    asb.append(source.getName() + " not found!");
                    asb.toAttributedString().println(terminal);
                    terminal.writer().flush();
                    open = false;
                    displayMessage = true;
                }
            }
        } while (!open && sourceIdx > 0);
        if (!open) {
            throw new FileNotFoundException(); 
        }
    }

    void moveTo(int lineNum) throws IOException {
        AttributedString line = getLine(lineNum);
        if (line != null){
            if (firstLineInMemory > lineNum) {
                openSource();
            }
            firstLineToDisplay = lineNum;
            offsetInLine = 0;
        } else {
            message = "Cannot seek to line number " + (lineNum + 1);
        }
    }
    
    private void moveToNextMatch() throws IOException {
        Pattern compiled = getPattern();
        Pattern dpCompiled = getPattern(true);
        if (compiled != null) {
            for (int lineNumber = firstLineToDisplay + 1; ; lineNumber++) {
                AttributedString line = getLine(lineNumber);
                if (line == null) {
                    break;
                } else if (!toBeDisplayed(line, dpCompiled)) {
                    continue;
                } else if (compiled.matcher(line).find()) {
                    firstLineToDisplay = lineNumber;
                    offsetInLine = 0;
                    return;
                }
            }
        }
        message = "Pattern not found";
    }

    private void moveToPreviousMatch() throws IOException {
        Pattern compiled = getPattern();
        Pattern dpCompiled = getPattern(true);
        if (compiled != null) {
            for (int lineNumber = firstLineToDisplay - 1; lineNumber >= firstLineInMemory; lineNumber--) {
                AttributedString line = getLine(lineNumber);
                if (line == null) {
                    break;
                } else if (!toBeDisplayed(line, dpCompiled)) {
                    continue;
                } else if (compiled.matcher(line).find()) {
                    firstLineToDisplay = lineNumber;
                    offsetInLine = 0;
                    return;
                }
            }
        }
        message = "Pattern not found";
    }

    private String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ESCAPE) {
                sb.append("ESC");
            } else if (c < 32) {
                sb.append('^').append((char) (c + '@'));
            } else if (c < 128) {
                sb.append(c);
            } else {
                sb.append('\\').append(String.format("%03o", (int) c));
            }
        }
        return sb.toString();
    }

    void moveForward(int lines) throws IOException {
        Pattern dpCompiled = getPattern(true);
        int width = size.getColumns() - (printLineNumbers ? 8 : 0);
        int height = size.getRows();
        boolean doOffsets = firstColumnToDisplay == 0 && !chopLongLines;
        if (lines == Integer.MAX_VALUE) {
            Long allLines = sources.get(sourceIdx).lines();
            if (allLines != null) {
                firstLineToDisplay = (int)(long)allLines;
                for (int l = 0; l < height - 1; l++) {
                    firstLineToDisplay = prevLine2display(firstLineToDisplay, dpCompiled).getU();
                }
            }
        }
        while (--lines >= 0) {
            int lastLineToDisplay = firstLineToDisplay;
            if (!doOffsets) {
                for (int l = 0; l < height - 1; l++) {
                    lastLineToDisplay = nextLine2display(lastLineToDisplay, dpCompiled).getU();
                }
            } else {
                int off = offsetInLine;
                for (int l = 0; l < height - 1; l++) {
                    Pair<Integer, AttributedString> nextLine = nextLine2display(lastLineToDisplay, dpCompiled);
                    AttributedString line = nextLine.getV();
                    if (line == null) {
                        lastLineToDisplay = nextLine.getU();
                        break;
                    }
                    if (line.columnLength() > off + width) {
                        off += width;
                    } else {
                        off = 0;
                        lastLineToDisplay = nextLine.getU();
                    }
                }
            }
            if (getLine(lastLineToDisplay) == null) {
                eof();
                return;
            }
            Pair<Integer, AttributedString> nextLine = nextLine2display(firstLineToDisplay, dpCompiled);
            AttributedString line = nextLine.getV();
            if (doOffsets && line.columnLength() > width + offsetInLine) {
                offsetInLine += width;
            } else {
                offsetInLine = 0;
                firstLineToDisplay = nextLine.getU();
            }
        }
    }

    void moveBackward(int lines) throws IOException {
        Pattern dpCompiled = getPattern(true);
        int width = size.getColumns() - (printLineNumbers ? 8 : 0);
        while (--lines >= 0) {
            if (offsetInLine > 0) {
                offsetInLine = Math.max(0, offsetInLine - width);
            } else if (firstLineInMemory < firstLineToDisplay) {
                Pair<Integer, AttributedString> prevLine = prevLine2display(firstLineToDisplay, dpCompiled);
                firstLineToDisplay = prevLine.getU();
                AttributedString line = prevLine.getV();
                if (line != null && firstColumnToDisplay == 0 && !chopLongLines) {
                    int length = line.columnLength();
                    offsetInLine = length - length % width;
                }
            } else {
                bof();
                return;
            }
        }
    }

    private void eof() {
        nbEof++;
        if (sourceIdx > 0 && sourceIdx < sources.size() - 1) {
            message = "(END) - Next: " + sources.get(sourceIdx + 1).getName();
        } else {
            message = "(END)";
        }
        if (!quiet && !veryQuiet && !quitAtFirstEof && !quitAtSecondEof) {
            terminal.puts(Capability.bell);
            terminal.writer().flush();
        }
    }

    private void bof() {
        if (!quiet && !veryQuiet) {
            terminal.puts(Capability.bell);
            terminal.writer().flush();
        }
    }

    int getStrictPositiveNumberInBuffer(int def) {
        try {
            int n = Integer.parseInt(buffer.toString());
            return (n > 0) ? n : def;
        } catch (NumberFormatException e) {
            return def;
        } finally {
            buffer.setLength(0);
        }
    }
    
    private Pair<Integer, AttributedString> nextLine2display(int line, Pattern dpCompiled) throws IOException {
        AttributedString curLine = null;
        do {
            curLine = getLine(line++);
        } while (!toBeDisplayed(curLine, dpCompiled));
        return new Pair<>(line, curLine);
    }
    
    private Pair<Integer, AttributedString> prevLine2display(int line, Pattern dpCompiled) throws IOException {
        AttributedString curLine = null;
        do {
            curLine = getLine(line--);
        } while (line > 0 && !toBeDisplayed(curLine, dpCompiled));
        if (line == 0 && !toBeDisplayed(curLine, dpCompiled)) {
            curLine = null;
        }
        return new Pair<>(line, curLine);
    }

    private boolean toBeDisplayed(AttributedString curLine, Pattern dpCompiled) {
        return curLine == null || dpCompiled == null || sourceIdx == 0 || dpCompiled.matcher(curLine).find();
    }
    
    synchronized boolean display(boolean oneScreen) throws IOException {
        return display(oneScreen, null);
    }
    
    synchronized boolean display(boolean oneScreen, Integer curPos) throws IOException {
        List<AttributedString> newLines = new ArrayList<>();
        int width = size.getColumns() - (printLineNumbers ? 8 : 0);
        int height = size.getRows();
        int inputLine = firstLineToDisplay;
        AttributedString curLine = null;
        Pattern compiled = getPattern();
        Pattern dpCompiled = getPattern(true);
        boolean fitOnOneScreen = false;
        boolean eof = false;
        for (int terminalLine = 0; terminalLine < height - 1; terminalLine++) {
            if (curLine == null) {
                Pair<Integer, AttributedString> nextLine = nextLine2display(inputLine, dpCompiled);
                inputLine = nextLine.getU();
                curLine = nextLine.getV();
                if (curLine == null) {
                    if (oneScreen) {
                        fitOnOneScreen = true;
                        break;
                    }
                    eof = true;
                    curLine = new AttributedString("~");
                }
                if (compiled != null) {
                    curLine = curLine.styleMatches(compiled, AttributedStyle.DEFAULT.inverse());
                }
            }
            AttributedString toDisplay;
            if (firstColumnToDisplay > 0 || chopLongLines) {
                int off = firstColumnToDisplay;
                if (terminalLine == 0 && offsetInLine > 0) {
                    off = Math.max(offsetInLine, off);
                }
                toDisplay = curLine.columnSubSequence(off, off + width);
                curLine = null;
            } else {
                if (terminalLine == 0 && offsetInLine > 0) {
                    curLine = curLine.columnSubSequence(offsetInLine, Integer.MAX_VALUE);
                }
                toDisplay = curLine.columnSubSequence(0, width);
                curLine = curLine.columnSubSequence(width, Integer.MAX_VALUE);
                if (curLine.length() == 0) {
                    curLine = null;
                }
            }
            if (printLineNumbers && !eof) {
                AttributedStringBuilder sb = new AttributedStringBuilder();
                sb.append(String.format("%7d ", inputLine));
                sb.append(toDisplay);
                newLines.add(sb.toAttributedString());
            } else {
                newLines.add(toDisplay);
            }
        }
        if (oneScreen) {
            if (fitOnOneScreen) {
                newLines.forEach(l -> l.println(terminal));
            }
            return fitOnOneScreen;
        }
        AttributedStringBuilder msg = new AttributedStringBuilder();
        if (MESSAGE_FILE_INFO.equals(message)){
            Source source = sources.get(sourceIdx);
            Long allLines = source.lines();
            message = source.getName() 
                    + (sources.size() > 2 ? " (file " + sourceIdx + " of " + (sources.size() - 1) + ")" : "")
                    + " lines " + (firstLineToDisplay + 1) + "-" + inputLine + "/" + (allLines != null ? allLines : lines.size())
                    + (eof ? " (END)" : "");
        }
        if (buffer.length() > 0) {
            msg.append(" ").append(buffer);
        } else if (bindingReader.getCurrentBuffer().length() > 0
                && terminal.reader().peek(1) == NonBlockingReader.READ_EXPIRED) {
            msg.append(" ").append(printable(bindingReader.getCurrentBuffer()));
        } else if (message != null) {
            msg.style(AttributedStyle.INVERSE);
            msg.append(message);
            msg.style(AttributedStyle.INVERSE.inverseOff());
        } else if (displayPattern != null) {
            msg.append("&");
        } else {
            msg.append(":");
        }
        newLines.add(msg.toAttributedString());

        display.resize(size.getRows(), size.getColumns());
        if (curPos == null) {
            display.update(newLines, -1);
        } else {
            display.update(newLines, size.cursorPos(size.getRows() - 1, curPos + 1));            
        }
        return false;
    }

    private Pattern getPattern() {
        return getPattern(false);
    }

    private Pattern getPattern(boolean doDisplayPattern) {
        Pattern compiled = null;
        String _pattern = doDisplayPattern ? displayPattern : pattern;
        if (_pattern != null) {
            boolean insensitive = ignoreCaseAlways || ignoreCaseCond && _pattern.toLowerCase().equals(_pattern);
            compiled = Pattern.compile("(" + _pattern + ")", insensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        }
        return compiled;
    }

    AttributedString getLine(int line) throws IOException {
        while (line >= lines.size()) {
            String str = reader.readLine();
            if (str != null) {
                lines.add(AttributedString.fromAnsi(str, tabs));
            } else {
                break;
            }
        }
        if (line < lines.size()) {
            return lines.get(line);
        }
        return null;
    }

    /**
     * This is for long running commands to be interrupted by ctrl-c
     *
     * @throws InterruptedException if the thread has been interruped
     */
    public static void checkInterrupted() throws InterruptedException {
        Thread.yield();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private void bindKeys(KeyMap<Operation> map) {
        map.bind(Operation.HELP, "h", "H");
        map.bind(Operation.EXIT, "q", ":q", "Q", ":Q", "ZZ");
        map.bind(Operation.FORWARD_ONE_LINE, "e", ctrl('E'), "j", ctrl('N'), "\r", key(terminal, Capability.key_down));
        map.bind(Operation.BACKWARD_ONE_LINE, "y", ctrl('Y'), "k", ctrl('K'), ctrl('P'), key(terminal, Capability.key_up));
        map.bind(Operation.FORWARD_ONE_WINDOW_OR_LINES, "f", ctrl('F'), ctrl('V'), " ");
        map.bind(Operation.BACKWARD_ONE_WINDOW_OR_LINES, "b", ctrl('B'), alt('v'));
        map.bind(Operation.FORWARD_ONE_WINDOW_AND_SET, "z");
        map.bind(Operation.BACKWARD_ONE_WINDOW_AND_SET, "w");
        map.bind(Operation.FORWARD_ONE_WINDOW_NO_STOP, alt(' '));
        map.bind(Operation.FORWARD_HALF_WINDOW_AND_SET, "d", ctrl('D'));
        map.bind(Operation.BACKWARD_HALF_WINDOW_AND_SET, "u", ctrl('U'));
        map.bind(Operation.RIGHT_ONE_HALF_SCREEN, alt(')'), key(terminal, Capability.key_right));
        map.bind(Operation.LEFT_ONE_HALF_SCREEN, alt('('), key(terminal, Capability.key_left));
        map.bind(Operation.FORWARD_FOREVER, "F");
        map.bind(Operation.REPAINT, "r", ctrl('R'), ctrl('L'));
        map.bind(Operation.REPAINT_AND_DISCARD, "R");        
        map.bind(Operation.REPEAT_SEARCH_FORWARD, "n");
        map.bind(Operation.REPEAT_SEARCH_BACKWARD, "N");
        map.bind(Operation.REPEAT_SEARCH_FORWARD_SPAN_FILES, alt('n'));
        map.bind(Operation.REPEAT_SEARCH_BACKWARD_SPAN_FILES, alt('N'));
        map.bind(Operation.UNDO_SEARCH, alt('u'));
        map.bind(Operation.GO_TO_FIRST_LINE_OR_N, "g", "<", alt('<'));
        map.bind(Operation.GO_TO_LAST_LINE_OR_N, "G", ">", alt('>'));
        map.bind(Operation.HOME, key(terminal, Capability.key_home));
        map.bind(Operation.END, key(terminal, Capability.key_end));
        map.bind(Operation.ADD_FILE, ":e", ctrl('X') + ctrl('V'));
        map.bind(Operation.NEXT_FILE, ":n");
        map.bind(Operation.PREV_FILE, ":p");
        map.bind(Operation.GOTO_FILE, ":x");
        map.bind(Operation.INFO_FILE, "=", ":f", ctrl('G'));
        map.bind(Operation.DELETE_FILE, ":d");
        map.bind(Operation.BACKSPACE, del());
        "-/0123456789?&".chars().forEach(c -> map.bind(Operation.CHAR, Character.toString((char) c)));
    }

    protected enum Operation {

        // General
        HELP,
        EXIT,

        // Moving
        FORWARD_ONE_LINE,
        BACKWARD_ONE_LINE,
        FORWARD_ONE_WINDOW_OR_LINES,
        BACKWARD_ONE_WINDOW_OR_LINES,
        FORWARD_ONE_WINDOW_AND_SET,
        BACKWARD_ONE_WINDOW_AND_SET,
        FORWARD_ONE_WINDOW_NO_STOP,
        FORWARD_HALF_WINDOW_AND_SET,
        BACKWARD_HALF_WINDOW_AND_SET,
        LEFT_ONE_HALF_SCREEN,
        RIGHT_ONE_HALF_SCREEN,
        FORWARD_FOREVER,
        REPAINT,
        REPAINT_AND_DISCARD,

        // Searching
        REPEAT_SEARCH_FORWARD,
        REPEAT_SEARCH_BACKWARD,
        REPEAT_SEARCH_FORWARD_SPAN_FILES,
        REPEAT_SEARCH_BACKWARD_SPAN_FILES,
        UNDO_SEARCH,

        // Jumping
        GO_TO_FIRST_LINE_OR_N,
        GO_TO_LAST_LINE_OR_N,
        GO_TO_PERCENT_OR_N,
        GO_TO_NEXT_TAG,
        GO_TO_PREVIOUS_TAG,
        FIND_CLOSE_BRACKET,
        FIND_OPEN_BRACKET,

        // Options
        OPT_PRINT_LINES,
        OPT_CHOP_LONG_LINES,
        OPT_QUIT_AT_FIRST_EOF,
        OPT_QUIT_AT_SECOND_EOF,
        OPT_QUIET,
        OPT_VERY_QUIET,
        OPT_IGNORE_CASE_COND,
        OPT_IGNORE_CASE_ALWAYS,

        // Files
        ADD_FILE,
        NEXT_FILE,
        PREV_FILE,
        GOTO_FILE,
        INFO_FILE,
        DELETE_FILE,
        
        // 
        CHAR,

        // Edit pattern
        INSERT,
        RIGHT,
        LEFT,
        NEXT_WORD,
        PREV_WORD,
        HOME,
        END,
        BACKSPACE,
        DELETE,
        DELETE_WORD,
        DELETE_LINE,
        ACCEPT,
        UP,
        DOWN
    }

    static class InterruptibleInputStream extends FilterInputStream {
        InterruptibleInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException();
            }
            return super.read(b, off, len);
        }
    }

    static class Pair<U,V> {
        final U u; final V v;
        public Pair(U u, V v) {
            this.u = u;
            this.v = v;
        }
        public U getU() {
            return u;
        }
        public V getV() {
            return v;
        }
    }

}
