const fs = require('fs');
const path = require('path');
const { encode } = require('plantuml-encoder');
const http = require('http');

const diagramsDir = path.join(__dirname);
const outputDir = path.join(__dirname);

// Hand-drawn diagrams only. The parsed ones under codekarta/ come from
// tools/generate-architecture-diagrams.sh instead. class-diagram.puml was retired to
// archive/ once code-karta parsed the same picture from source — see archive/README.md.
const files = [
    'component-diagram.puml',
    'build-sequence.puml',
    'data-flow.puml',
    'platform-output.puml',
];

files.forEach(file => {
    const content = fs.readFileSync(path.join(diagramsDir, file), 'utf8');
    const encoded = encode(content);
    const url = `http://www.plantuml.com/plantuml/png/${encoded}`;

    console.log(`Generating ${file.replace('.puml', '.png')}...`);

    const outputPath = path.join(outputDir, file.replace('.puml', '.png'));
    const imgFile = fs.createWriteStream(outputPath);

    http.get(url, (response) => {
        response.pipe(imgFile);
        imgFile.on('finish', () => {
            imgFile.close();
            console.log(`✓ Saved: ${outputPath}`);
        });
    }).on('error', (err) => {
        console.error(`✗ Error: ${err.message}`);
        fs.unlink(outputPath, () => {});
    });
});
