const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');

// Выход кладём в target/classes/META-INF/resources: Spring Boot отдаёт такую статику из jar.
const outputPath = path.resolve(__dirname, 'target/classes/META-INF/resources');

// Для разработки: адрес локального admin-console, на который проксируются конфиг и API.
const adminConsoleUrl = process.env.ADMIN_CONSOLE_URL || 'http://localhost:8082';

module.exports = {
  entry: './src/index.tsx',
  output: {
    path: outputPath,
    filename: '[name].[contenthash].js',
    publicPath: '/',
    clean: true,
  },
  resolve: { extensions: ['.tsx', '.ts', '.js'] },
  module: {
    rules: [
      { test: /\.tsx?$/, loader: 'ts-loader', exclude: /node_modules/, options: { configFile: 'tsconfig.json' } },
      { test: /\.css$/, use: ['style-loader', 'css-loader'] },
    ],
  },
  plugins: [new HtmlWebpackPlugin({ template: './src/index.html' })],
  devtool: 'source-map',
  devServer: {
    port: 3000,
    historyApiFallback: true,
    proxy: [
      {
        context: ['/console-config.json', '/api'],
        target: adminConsoleUrl,
        changeOrigin: true,
      },
    ],
  },
};
