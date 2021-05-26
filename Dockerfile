FROM tomcat:9.0
ENV GITBLIT_HOME=/opt/gitblit-data

# Prepare Environment
RUN groupadd git \
&& useradd -g git git \
&& apt-get --yes update \
&& apt-get --yes install redis-server \
&& apt-get clean \
&& mkdir -p /opt/gitblit-data \
&& rm -r /usr/local/tomcat/webapps/*

# Install Gitblit
COPY ./build/target/*.war  /usr/local/tomcat/webapps/ROOT/
RUN cd /usr/local/tomcat/webapps/ROOT/ &&  unzip  *.war && rm *.war \
&& mv /usr/local/tomcat/webapps/ROOT/WEB-INF/data/* /opt/gitblit-data \
&& chown -R git:git /usr/local/tomcat/ \
&& chown -R git:git /opt/gitblit-data

# Adjust the default Gitblit settings to bind to 80, 443, 9418, 29418, and allow RPC administration.

RUN echo "web.enableRpcManagement=true" >> /opt/gitblit-data/gitblit.properties \
&& echo "web.enableRpcAdministration=true" >> /opt/gitblit-data/gitblit.properties

# Setup the Docker container environment and run Gitblit
WORKDIR /usr/local/tomcat/webapps/ROOT
VOLUME /opt/gitblit-data/git
VOLUME /usr/local/tomcat/logs
EXPOSE 8080
EXPOSE 9418
EXPOSE 29418
USER git
