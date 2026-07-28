class TranscriptionKaraoke {
    constructor(options = {}) {
        this.videoElement = options.videoElement;
        this.chunkElements = options.chunkElements || [];
        this.wordElements = options.wordElements || [];
        this.onWordChange = options.onWordChange || (() => {});
        this.onChunkChange = options.onChunkChange || (() => {});
        
        this.currentWordIndex = -1;
        this.currentChunkIndex = -1;
        this.isPlaying = false;
        
        this.init();
    }
    
    init() {
        if (!this.videoElement) {
            console.warn('TranscriptionKaraoke: No video element provided');
            return;
        }
        
        this.videoElement.addEventListener('timeupdate', () => this.onTimeUpdate());
        this.videoElement.addEventListener('play', () => { this.isPlaying = true; });
        this.videoElement.addEventListener('pause', () => { this.isPlaying = false; });
        this.videoElement.addEventListener('seeked', () => this.onSeeked());
        
        this.buildWordIndex();
    }
    
    buildWordIndex() {
        this.wordIndex = [];
        
        this.chunkElements.forEach((chunkEl, chunkIdx) => {
            const wordEls = chunkEl.querySelectorAll('[data-word]');
            wordEls.forEach((wordEl, wordIdx) => {
                const start = parseFloat(wordEl.dataset.wordStart);
                const end = parseFloat(wordEl.dataset.wordEnd);
                if (!isNaN(start) && !isNaN(end)) {
                    this.wordIndex.push({
                        element: wordEl,
                        chunkIndex: chunkIdx,
                        wordIndex: wordIdx,
                        start: start,
                        end: end
                    });
                }
            });
        });
        
        this.wordIndex.sort((a, b) => a.start - b.start);
    }
    
    onTimeUpdate() {
        if (!this.videoElement || !this.wordIndex.length) return;
        
        const currentTime = this.videoElement.currentTime;
        
        let newWordIndex = -1;
        for (let i = 0; i < this.wordIndex.length; i++) {
            const word = this.wordIndex[i];
            if (currentTime >= word.start && currentTime < word.end) {
                newWordIndex = i;
                break;
            }
        }
        
        if (newWordIndex !== this.currentWordIndex) {
            this.highlightWord(newWordIndex);
        }
    }
    
    highlightWord(wordIndex) {
        if (this.currentWordIndex >= 0 && this.currentWordIndex < this.wordIndex.length) {
            const prevWord = this.wordIndex[this.currentWordIndex];
            prevWord.element.classList.remove('karaoke-active');
            prevWord.element.classList.add('karaoke-passed');
        }
        
        this.currentWordIndex = wordIndex;
        
        if (wordIndex >= 0 && wordIndex < this.wordIndex.length) {
            const word = this.wordIndex[wordIndex];
            word.element.classList.remove('karaoke-passed');
            word.element.classList.add('karaoke-active');
            
            this.ensureWordVisible(word.element);
            
            const newChunkIndex = word.chunkIndex;
            if (newChunkIndex !== this.currentChunkIndex) {
                this.currentChunkIndex = newChunkIndex;
                this.onChunkChange(newChunkIndex);
            }
            
            this.onWordChange(word, wordIndex);
        } else {
            this.onWordChange(null, -1);
        }
    }
    
    ensureWordVisible(wordElement) {
        const container = document.getElementById('transcript-content');
        if (!container) return;
        
        const wordRect = wordElement.getBoundingClientRect();
        const containerRect = container.getBoundingClientRect();
        
        if (wordRect.bottom > containerRect.bottom || wordRect.top < containerRect.top) {
            wordElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
    }
    
    onSeeked() {
        this.onTimeUpdate();
    }
    
    setChunkElements(chunkElements) {
        this.chunkElements = chunkElements;
        this.buildWordIndex();
    }
    
    jumpToTime(seconds) {
        if (this.videoElement) {
            this.videoElement.currentTime = seconds;
        }
    }
    
    jumpToWord(wordIndex) {
        if (wordIndex >= 0 && wordIndex < this.wordIndex.length) {
            this.jumpToTime(this.wordIndex[wordIndex].start);
        }
    }
    
    jumpToChunk(chunkIndex) {
        const chunkWords = this.wordIndex.filter(w => w.chunkIndex === chunkIndex);
        if (chunkWords.length > 0) {
            this.jumpToTime(chunkWords[0].start);
        }
    }
}

function initKaraoke(videoSelector, transcriptSelector, options = {}) {
    const videoEl = document.querySelector(videoSelector);
    const transcriptEl = document.querySelector(transcriptSelector);
    
    if (!videoEl || !transcriptEl) {
        console.warn('initKaraoke: Could not find video or transcript element');
        return null;
    }
    
    const chunkElements = Array.from(transcriptEl.querySelectorAll('.transcript-chunk'));
    
    return new TranscriptionKaraoke({
        videoElement: videoEl,
        chunkElements: chunkElements,
        onWordChange: options.onWordChange,
        onChunkChange: options.onChunkChange
    });
}

function createWordElements(chunkText, chunkStart, chunkEnd, wordsInChunk) {
    if (!wordsInChunk || wordsInChunk.length === 0) {
        return `<span>${escapeHtml(chunkText)}</span>`;
    }
    
    let html = '';
    let charIndex = 0;
    
    wordsInChunk.forEach((wordInfo, i) => {
        const word = wordInfo.word;
        const start = wordInfo.start;
        const end = wordInfo.end;
        
        const beforeWord = chunkText.substring(charIndex, chunkText.indexOf(word, charIndex));
        if (beforeWord) {
            html += escapeHtml(beforeWord);
        }
        
        html += `<span class="karaoke-word" data-word="${escapeHtml(word)}" data-word-start="${start}" data-word-end="${end}">${escapeHtml(word)}</span>`;
        
        charIndex = chunkText.indexOf(word, charIndex) + word.length;
    });
    
    const remaining = chunkText.substring(charIndex);
    if (remaining) {
        html += escapeHtml(remaining);
    }
    
    return html;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function formatTime(seconds) {
    const total = Math.round(seconds);
    const mins = Math.floor(total / 60);
    const secs = total % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { TranscriptionKaraoke, initKaraoke, createWordElements, escapeHtml, formatTime };
}