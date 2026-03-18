FROM openjdk:21-ea
 
WORKDIR /usrapp/bin
 
COPY target/classes /usrapp/bin/classes
COPY target/dependency /usrapp/bin/dependency

EXPOSE 35000

CMD ["java","-cp","./classes:./dependency/*","com.co.edu.escuelaing.lab7.MicroSpringBootG4","35000"]