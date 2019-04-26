from kdvolder/jdk8

MAINTAINER YT "<21880422@qq.com>"

WORKDIR /sentinel-dashboard
ADD . /sentinel-dashboard
RUN rm -rf /sentinel-dashboard/.git*

EXPOSE 8080
CMD ["java", "-jar", "sentinel-dashboard.jar"]