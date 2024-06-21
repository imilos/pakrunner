FROM ubuntu:focal

# Install dependencies
RUN apt-get update -y && apt-get install openjdk-17-jre-headless lsof mpich zip bc python-is-python2 wget apt-transport-https curl -y

# Install dotnet core
RUN wget https://packages.microsoft.com/config/ubuntu/20.04/packages-microsoft-prod.deb -O packages-microsoft-prod.deb && dpkg -i packages-microsoft-prod.deb
RUN apt-get update -y && apt-get install dotnet-runtime-3.1 -y

RUN addgroup --gid 1000 milos
RUN adduser --disabled-password --gecos '' --uid 1000 --gid 1000 milos
USER milos

# set a directory for the app
WORKDIR /pakrunner

# copy all the files to the container
COPY . .

# define the port number the container should expose
EXPOSE 8080

# run the command
CMD ["java", "-jar", "target/pakrunner-0.0.1-SNAPSHOT.jar"]

