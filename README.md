# pakrunner

*Pakrunner* je REST servis za kontrolu i monitoring dugotrajnih proračuna. Servis je nastao iz potrebe da se dugotrajni proračuni zasnovani na metodi konačnih elemenata pokreću preko standardizovanog REST interfejsa iz bilo koje aplikacije, iz bilo kog operativnog sistema, običnim HTTP pozivima. Pored mogućnosti pokretanja proračuna, servis omogućava i mnoge druge funkcionalnosti, kao što su čitanje logova u realnom vremenu, rad sa posebnim poslovima, upit statusa, operacije sa fajlovima i direktorijumima, _upload_ i preuzimanje rezultata proračuna itd.

Zamišljeno je da servis radi u okviru bezbednog okruženja, kao što je VPN (_Virtual Private Network_), pa u protokol za sada nije ugrađeno ništa od sigurnosnih protokola. Implementacija sigurnosnih mehanizama planirana je za neku narednu verziju. 

## Primeri poziva

### Echo poziv (za testiranje)
`curl -d '{"guid":"3333-5555", "command":"./proba.sh"}' -H "Content-Type: application/json" -X POST http://147.91.200.5:8081/pakrunner/rest/api/echo`

### Kreiranje novog posla
`curl -d '{"guid":"3333-4444"}' -H "Content-Type: application/json" -X POST http://147.91.200.5:8081/pakrunner/rest/api/createnew`

### Startovanje posla
`curl -d '{"guid":"3333-4444", "command":"./proba.sh"}' -H "Content-Type: application/json" -X POST http://147.91.200.5:8081/pakrunner/rest/api/start`

### Da li posao radi?
`curl -H "Content-Type: application/json" -X GET http://147.91.200.5:8081/pakrunner/rest/api/isrunning/3333-4444`

### Zaustavljanje posla
`curl -d '{"guid":"3333-4444"}' -H "Content-Type: application/json" -X POST http://147.91.200.5:8081/pakrunner/rest/api/stop`

### Lista poslova
`curl -H "Content-Type: application/json" -X GET http://147.91.200.5:8081pakrunner/rest/api/tasklist`

### Poslednjih `n` linija loga za posao `guid`. Ako je n=0, preuzima se ceo log
`curl -H "Content-Type: application/json" -X GET http://147.91.200.5:8081/pakrunner/rest/api/logtail/3333-4444/4`

### Preuzimanje log fajla za posao `guid`
`curl -H "Content-Type: application/json" -X GET http://147.91.200.5:8081/pakrunner/rest/api/logdownload/3333-4444`

### Preuzmi rezultate u zip arhivi
`curl -d '{"guid":"3333-4444", "files":["proba.sh","pak.log"]}' -H "Content-Type: application/json" -X POST http://147.91.200.5:8081/pakrunner/rest/api/getresults --output rezultati.zip`

### Uklanjanje posla
`curl -H "Content-Type: application/json" -X GET http://147.91.200.5:8081/pakrunner/rest/api/remove/3333-4444/`

### Brisanje svih poslova
`curl -H "Content-Type: application/json" -X GET http://147.91.200.5:8081/pakrunner/rest/api/removeall`

### Upload zip-a i raspakovavanje u radni direktorijum posla `guid`
`curl -F 'file=@proba.zip' -F 'guid=3333-1111' -X POST http://147.91.200.5:8081/pakrunner/rest/api/uploadzip`

### Kopiranje fajla iz podfoldera u radni folder posla. Navodi se relativna putanja i ciljno ime fajla
`curl -d '{"guid":"3333-1111", "path":"L10/ttt.txt", "name":"ttt-kopija.txt"}' -H "Content-Type: application/json" -X POST http://147.91.200.5:8081/pakrunner/rest/api/localcopy`

### Kopiranje fajla iz radnog direktorijuma `guidsrc` u direktorijum `guiddest`
`curl -d '{"guidsrc":"3333-1111", "guiddest":"3333-2222", "namesrc":"pom.xml", "namedest":"pom.xml"}' -H "Content-Type: application/json" -X POST http://147.91.200.5:8081/pakrunner/rest/api/copyfiletasktotask`

### Brisanje fajla iz radnog direktorijuma (može i relativna putanja)
`curl -d '{"guid":"3333-1111", "path":"ttt.txt"}' -H "Content-Type: application/json" -X POST http://147.91.200.5:8081/pakrunner/rest/api/removefile`

### Preimenovanje fajla u radnom direktorijumu (može i relativna putanja)
`curl -d '{"guid":"3333-1111", "pathold":"Ulaz.csv", "pathnew":"Ulaz1.csv"}' -H "Content-Type: application/json" -X POST http://147.91.200.5:8081/pakrunner/rest/api/renamefile`

### Listing direktorijuma. Ako je `path` prazan, lista se radni direktorijum `guid`. Vraća posebno niz fajlova, a posebno niz direktorijuma.
`curl -d '{"guid":"3333-4444", "path":"/L10"}' -H "Content-Type: application/json" -X POST http://147.91.200.5:8081/pakrunner/rest/api/listfiles`


