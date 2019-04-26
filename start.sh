cd sentinel-dashboard

mvn clean package -U -e -Dmaven.normal.skip=true  -Dmaven.test.skip=true

cd target/

cp ../Dockerfile ./Dockerfile

docker build --pull=true -t hub.mzh.com:sentinel-dashboard ./

docker stop sentinel-dashboard

docker run -d --name=sentinel-dashboard hub.mzh.com:sentinel-dashboard