// titan-screenprobe is a development client of the remote desktop protocol
// (ADR-0029): it runs a command that speaks `titan-agent --screen` on its
// stdin and stdout (for example `docker exec -i <container> titan-agent
// --screen`, or `ssh <host> titan-agent --screen`), shows the frames it gets
// for a while, sends some input, and saves the last image as a PNG.
//
//	titan-screenprobe -out shot.png -for 5s -click 200,150 -type "hola ñ" -- <command…>
package main

import (
	"bytes"
	"compress/zlib"
	"flag"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"log"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

func main() {
	out := flag.String("out", "screen.png", "where to save the last image")
	dur := flag.Duration("for", 5*time.Second, "how long to watch")
	click := flag.String("click", "", "x,y to move to and click after the first frame; several separated by ;")
	move := flag.String("move", "", "x,y to move the pointer to after the first frame")
	text := flag.String("type", "", "text to type after the first frame (and the click)")
	keys := flag.String("keys", "", "keys to press after the rest, comma separated: esc, tab, enter, space, left, right, up, down (shift+tab and the like too)")
	rel := flag.String("rel", "", "with -input: dx,dy to move the pointer by")
	scroll := flag.Int("scroll", 0, "notches to scroll after the first frame (> 0 up)")
	inputOnly := flag.Bool("input", false, "speak to `titan-agent --input` (a mouse pad): no screen, the input after INPUT_READY")
	flag.Parse()
	if flag.NArg() == 0 {
		log.Fatal("usage: titan-screenprobe [flags] -- command...")
	}
	cmd := exec.Command(flag.Arg(0), flag.Args()[1:]...)
	cmd.Stderr = os.Stderr
	stdin, _ := cmd.StdinPipe()
	stdout, _ := cmd.StdoutPipe()
	if err := cmd.Start(); err != nil {
		log.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill() }()
	send := func(f protocol.Frame) {
		if _, err := stdin.Write(protocol.Encode(f)); err != nil {
			log.Fatal("send: ", err)
		}
	}
	if !*inputOnly {
		send(protocol.Frame{Type: protocol.TypeScreenHello, Version: protocol.ScreenVersion, Quality: 0})
	}

	frames := make(chan protocol.Frame, 256)
	go func() {
		defer close(frames)
		dec := &protocol.Decoder{Max: protocol.MaxScreenPayload}
		buf := make([]byte, 256*1024)
		for {
			n, err := stdout.Read(buf)
			if n > 0 {
				fs, derr := dec.Feed(buf[:n])
				for _, f := range fs {
					frames <- f
				}
				if derr != nil {
					log.Print("decode: ", derr)
					return
				}
			}
			if err != nil {
				if err != io.EOF {
					log.Print("read: ", err)
				}
				return
			}
		}
	}()

	var img *image.RGBA
	start := time.Now()
	deadline := time.After(*dur)
	inputDone := false
	nFrames, nTiles := 0, 0
	var first time.Duration
loop:
	for {
		select {
		case f, ok := <-frames:
			if !ok {
				break loop
			}
			switch f.Type {
			case protocol.TypeScreenSize:
				log.Printf("SCREEN_SIZE %dx%d", f.Width, f.Height)
				img = image.NewRGBA(image.Rect(0, 0, f.Width, f.Height))
			case protocol.TypeScreenTile:
				nTiles++
				drawTile(img, f)
			case protocol.TypeScreenFrameEnd:
				nFrames++
				if nFrames == 1 {
					first = time.Since(start)
				}
				send(protocol.Frame{Type: protocol.TypeScreenAck, Seq: f.Seq})
				if !inputDone {
					inputDone = true
					sendInput(send, *move, *click, *text, *scroll)
					sendKeys(send, *keys)
				}
			case protocol.TypeCursorShape:
				log.Printf("CURSOR_SHAPE %dx%d hot %d,%d", f.Width, f.Height, f.X, f.Y)
			case protocol.TypeCursorPos:
				log.Printf("CURSOR_POS %d,%d visible=%v", f.X, f.Y, f.Visible)
			case protocol.TypeInputReady:
				log.Printf("INPUT_READY blocked=%v", f.Blocked)
				if *inputOnly && !inputDone {
					inputDone = true
					sendInput(send, *move, *click, *text, *scroll)
					sendKeys(send, *keys)
					if *rel != "" {
						if x, y, ok := xy(*rel); ok {
							send(protocol.Frame{Type: protocol.TypePointerMove, DX: int16(x), DY: int16(y)})
						}
					}
				}
			case protocol.TypeBye:
				log.Printf("BYE %q", f.Reason)
			}
		case <-deadline:
			break loop
		}
	}
	log.Printf("%d frames, %d tiles; first frame after %v", nFrames, nTiles, first)
	if *inputOnly {
		return
	}
	if img == nil {
		log.Fatal("no image")
	}
	w, err := os.Create(*out)
	if err != nil {
		log.Fatal(err)
	}
	defer w.Close()
	if err := png.Encode(w, img); err != nil {
		log.Fatal(err)
	}
	fmt.Println(*out)
}

func xy(s string) (int, int, bool) {
	a, b, ok := strings.Cut(s, ",")
	if !ok {
		return 0, 0, false
	}
	x, err1 := strconv.Atoi(a)
	y, err2 := strconv.Atoi(b)
	return x, y, err1 == nil && err2 == nil
}

func sendInput(send func(protocol.Frame), move, click, text string, scroll int) {
	if x, y, ok := xy(move); ok {
		send(protocol.Frame{Type: protocol.TypePointerTo, X: x, Y: y})
	}
	for _, c := range strings.Split(click, ";") {
		if x, y, ok := xy(c); ok {
			send(protocol.Frame{Type: protocol.TypePointerTo, X: x, Y: y})
			time.Sleep(300 * time.Millisecond)
			send(protocol.Frame{Type: protocol.TypePointerButton, Button: protocol.ButtonLeft, Pressed: true})
			time.Sleep(80 * time.Millisecond)
			send(protocol.Frame{Type: protocol.TypePointerButton, Button: protocol.ButtonLeft, Pressed: false})
			time.Sleep(400 * time.Millisecond)
		}
	}
	if text != "" {
		send(protocol.Frame{Type: protocol.TypeText, Bytes: []byte(text)})
	}
	if scroll != 0 {
		send(protocol.Frame{Type: protocol.TypeScroll, DY: int16(scroll * 120)})
	}
}

func drawTile(img *image.RGBA, f protocol.Frame) {
	if img == nil {
		return
	}
	switch f.Encoding {
	case protocol.EncodingFill:
		if len(f.Bytes) < 3 {
			return
		}
		c := color.RGBA{f.Bytes[0], f.Bytes[1], f.Bytes[2], 255}
		for y := f.Y; y < f.Y+f.Height; y++ {
			for x := f.X; x < f.X+f.Width; x++ {
				img.SetRGBA(x, y, c)
			}
		}
	case protocol.EncodingZlib:
		zr, err := zlib.NewReader(bytes.NewReader(f.Bytes))
		if err != nil {
			return
		}
		raw, _ := io.ReadAll(zr)
		for i := 0; i+2 < len(raw) && i/3 < f.Width*f.Height; i += 3 {
			p := i / 3
			img.SetRGBA(f.X+p%f.Width, f.Y+p/f.Width, color.RGBA{raw[i], raw[i+1], raw[i+2], 255})
		}
	case protocol.EncodingJPEG:
		j, err := jpeg.Decode(bytes.NewReader(f.Bytes))
		if err != nil {
			return
		}
		for y := 0; y < f.Height; y++ {
			for x := 0; x < f.Width; x++ {
				r, g, b, _ := j.At(x, y).RGBA()
				img.SetRGBA(f.X+x, f.Y+y, color.RGBA{uint8(r >> 8), uint8(g >> 8), uint8(b >> 8), 255})
			}
		}
	}
}

func sendKeys(send func(protocol.Frame), list string) {
	names := map[string]uint16{
		"esc": protocol.KeyEscape, "tab": protocol.KeyTab, "enter": protocol.KeyEnter, "space": protocol.KeySpace,
		"left": protocol.KeyArrowLeft, "right": protocol.KeyArrowRight, "up": protocol.KeyArrowUp, "down": protocol.KeyArrowDown,
	}
	for _, k := range strings.Split(list, ",") {
		if k == "" {
			continue
		}
		var mods uint8
		if rest, ok := strings.CutPrefix(k, "shift+"); ok {
			mods, k = protocol.ModShift, rest
		}
		code, ok := names[k]
		if !ok {
			log.Printf("unknown key %q", k)
			continue
		}
		send(protocol.Frame{Type: protocol.TypeKey, Key: code, Mods: mods, Action: protocol.ActionPress})
		time.Sleep(150 * time.Millisecond)
	}
}
